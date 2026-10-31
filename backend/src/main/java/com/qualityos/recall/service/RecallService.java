package com.qualityos.recall.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.quality.service.CapaService;
import com.qualityos.recall.dto.RecallDtos.AcknowledgementRequest;
import com.qualityos.recall.dto.RecallDtos.ClosureReview;
import com.qualityos.recall.dto.RecallDtos.ContactAttemptRequest;
import com.qualityos.recall.dto.RecallDtos.ContactView;
import com.qualityos.recall.dto.RecallDtos.CreateRecallRequest;
import com.qualityos.recall.dto.RecallDtos.HistoryEntry;
import com.qualityos.recall.dto.RecallDtos.RecallDashboard;
import com.qualityos.recall.dto.RecallDtos.RecallDetail;
import com.qualityos.recall.dto.RecallDtos.RecallReport;
import com.qualityos.recall.dto.RecallDtos.RecallSummary;
import com.qualityos.recall.dto.RecallDtos.ReportCustomer;
import com.qualityos.recall.dto.RecallDtos.ReportProduct;
import com.qualityos.recall.dto.RecallDtos.RequestClosureRequest;
import com.qualityos.recall.dto.RecallDtos.UpdateContactRequest;
import com.qualityos.recall.entity.Recall;
import com.qualityos.recall.entity.RecallAcknowledgement;
import com.qualityos.recall.entity.RecallBatch;
import com.qualityos.recall.entity.RecallContact;
import com.qualityos.recall.entity.RecallContactAttempt;
import com.qualityos.recall.repository.RecallAcknowledgementRepository;
import com.qualityos.recall.repository.RecallBatchRepository;
import com.qualityos.recall.repository.RecallContactAttemptRepository;
import com.qualityos.recall.repository.RecallContactRepository;
import com.qualityos.recall.repository.RecallRepository;
import com.qualityos.traceability.dto.TraceabilityDtos.CustomerResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.RecallLine;
import com.qualityos.traceability.dto.TraceabilityDtos.RecallScope;
import com.qualityos.traceability.service.DispatchService;
import com.qualityos.traceability.service.ProductionService;
import com.qualityos.traceability.service.TraceabilityService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecallService implements AttachmentPolicy {

    private static final String ENTITY = "Recall";
    private static final String ATTACHMENT_TYPE = "RECALL_CONTACT";
    private static final Set<RecallContact.Status> REACHED = Set.of(
            RecallContact.Status.CONTACTED, RecallContact.Status.ACKNOWLEDGED, RecallContact.Status.ACTION_REQUIRED,
            RecallContact.Status.RETURN_IN_PROGRESS, RecallContact.Status.RESOLVED);

    private final RecallRepository recalls;
    private final RecallBatchRepository batches;
    private final RecallContactRepository contacts;
    private final RecallContactAttemptRepository attempts;
    private final RecallAcknowledgementRepository acknowledgements;
    private final TraceabilityService traceability;
    private final ProductionService production;
    private final DispatchService dispatches;
    private final CapaService capas;
    private final OrganizationService organizations;
    private final TenantAccessService access;
    private final FacilityScope scope;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    RecallService(
            RecallRepository recalls,
            RecallBatchRepository batches,
            RecallContactRepository contacts,
            RecallContactAttemptRepository attempts,
            RecallAcknowledgementRepository acknowledgements,
            TraceabilityService traceability,
            ProductionService production,
            DispatchService dispatches,
            CapaService capas,
            OrganizationService organizations,
            TenantAccessService access,
            FacilityScope scope,
            DocumentNumberService numbers,
            AuditService audit) {
        this.recalls = recalls;
        this.batches = batches;
        this.contacts = contacts;
        this.attempts = attempts;
        this.acknowledgements = acknowledgements;
        this.traceability = traceability;
        this.production = production;
        this.dispatches = dispatches;
        this.capas = capas;
        this.organizations = organizations;
        this.access = access;
        this.scope = scope;
        this.numbers = numbers;
        this.audit = audit;
    }

    /** Opens a contact record per customer that received the batches, or anything made from them. */
    @Transactional
    public RecallDetail create(CreateRecallRequest request, Set<String> permissions) {
        Set<UUID> ids = request.batchNumbers().stream().map(String::trim)
                .map(number -> production.byNumber(number)
                        .orElseThrow(() -> new ResourceNotFoundException("Batch", number)).id())
                .collect(Collectors.toSet());
        scope.require(facilitiesOf(ids), "Batch", "");
        if (request.capaId() != null && capas.isClosed(request.capaId()).isEmpty()) {
            throw new ResourceNotFoundException("CAPA", request.capaId().toString());
        }
        Recall recall = recalls.save(new Recall(numbers.next("RCL"), request.recallDate(), request.reason().trim(),
                request.instructions().trim(), request.capaId()));
        ids.forEach(id -> batches.save(new RecallBatch(recall.getId(), id)));
        addMissingCustomers(recall, traceability.recallScope(ids, permissions));
        audit.record("RECALL_CREATED", ENTITY, recall.getId());
        return detail(recall, permissions);
    }

    @Transactional(readOnly = true)
    public PageResponse<RecallSummary> list(Pageable page) {
        var found = scope.restriction().map(allowed -> recalls.findWithin(allowed, page))
                .orElseGet(() -> recalls.findAll(page));
        Map<UUID, List<RecallBatch>> byRecall = batches.findByRecallIdIn(found.map(Recall::getId).toList()).stream()
                .collect(Collectors.groupingBy(RecallBatch::getRecallId));
        Map<UUID, String> names = production.views(byRecall.values().stream().flatMap(List::stream)
                        .map(RecallBatch::getProductionBatchId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, ProductionBatchView::batchNumber));
        return PageResponse.from(found, recall -> new RecallSummary(recall.getId(), recall.getRecallNumber(),
                recall.getRecallDate(), recall.getReason(), recall.getStatus().name(),
                byRecall.getOrDefault(recall.getId(), List.of()).stream()
                        .map(batch -> names.get(batch.getProductionBatchId())).sorted().toList()));
    }

    @Transactional(readOnly = true)
    public RecallDetail get(UUID id, Set<String> permissions) {
        return detail(load(id), permissions);
    }

    @Transactional(readOnly = true)
    public List<ContactView> customers(UUID id, String status, UUID customerId, UUID assignedTo, Set<String> permissions) {
        Recall recall = load(id);
        return views(recall, traceability.recallScope(batchIds(id), permissions)).stream()
                .filter(contact -> status == null || contact.status().equals(status))
                .filter(contact -> customerId == null || contact.customerId().equals(customerId))
                .filter(contact -> assignedTo == null || assignedTo.equals(contact.assignedTo()))
                .toList();
    }

    @Transactional
    public ContactView update(UUID id, UUID customerId, UpdateContactRequest request, Set<String> permissions) {
        RecallContact contact = editable(id, customerId);
        if (request.assignedTo() != null && !access.activeMemberIds().contains(request.assignedTo())) {
            throw new BusinessRuleException("Assign this to someone in the organisation.");
        }
        contact.setContactPerson(request.contactPerson());
        contact.setContactDetails(request.contactDetails());
        contact.setAssignedTo(request.assignedTo());
        contact.setFollowUpOn(request.followUpOn());
        contact.setNotes(request.notes());
        contacts.flush();
        audit.record("RECALL_CONTACT_UPDATED", ENTITY, id);
        return view(id, customerId, permissions);
    }

    /** Records that someone tried, whatever came of it. Earlier attempts stay as they were. */
    @Transactional
    public ContactView attempt(UUID id, UUID customerId, ContactAttemptRequest request, Set<String> permissions) {
        RecallContact contact = editable(id, customerId);
        if (request.attemptedAt().isAfter(Instant.now())) {
            throw new BusinessRuleException("An attempt cannot be in the future.");
        }
        attempts.save(new RecallContactAttempt(contact.getId(), request.method(), request.contactPerson(),
                request.contactDetails(), request.attemptedAt(), request.outcome(), request.customerResponse(),
                request.notes()));
        contact.attempted(request.outcome());
        if (request.followUpOn() != null) {
            contact.setFollowUpOn(request.followUpOn());
        }
        contacts.flush();
        audit.record("RECALL_CONTACT_ATTEMPTED", ENTITY, id, null,
                Map.of("customerId", customerId, "method", request.method(), "outcome", request.outcome()));
        return view(id, customerId, permissions);
    }

    @Transactional
    public ContactView acknowledge(UUID id, AcknowledgementRequest request, Set<String> permissions) {
        RecallContact contact = editable(id, request.customerId());
        if (request.acknowledgedOn().isAfter(today())) {
            throw new BusinessRuleException("An acknowledgement cannot be in the future.");
        }
        acknowledgements.save(new RecallAcknowledgement(contact.getId(), request.acknowledgedOn(),
                request.acknowledgedBy().trim(), request.customerResponse()));
        contact.acknowledged();
        contacts.flush();
        audit.record("RECALL_ACKNOWLEDGED", ENTITY, id, null, Map.of("customerId", request.customerId()));
        return view(id, request.customerId(), permissions);
    }

    @Transactional
    public ContactView changeStatus(UUID id, UUID customerId, RecallContact.Status status, Set<String> permissions) {
        RecallContact contact = editable(id, customerId);
        RecallContact.Status previous = contact.getStatus();
        List<UUID> record = List.of(contact.getId());
        contact.moveTo(status, attempts.findByContactRecordIdInOrderByAttemptedAt(record).size(),
                !acknowledgements.findByContactRecordIdInOrderByAcknowledgedOn(record).isEmpty());
        contacts.flush();
        audit.record("RECALL_CONTACT_STATUS_CHANGED", ENTITY, id, Map.of("status", previous),
                Map.of("customerId", customerId, "status", status));
        return view(id, customerId, permissions);
    }

    /** Lets a recall close without this customer, once someone accountable has said why. */
    @Transactional
    public ContactView recordException(UUID id, UUID customerId, String reason, Set<String> permissions) {
        RecallContact contact = editable(id, customerId);
        contact.recordException(reason.trim(), TenantContext.getCurrentUserId(), Instant.now());
        contacts.flush();
        audit.record("RECALL_EXCEPTION_RECORDED", ENTITY, id, null, Map.of("customerId", customerId, "reason", reason));
        return view(id, customerId, permissions);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID id) {
        load(id);
        return historyOf(contacts.findByRecallId(id));
    }

    @Transactional(readOnly = true)
    public RecallDashboard dashboard(UUID id, Set<String> permissions) {
        load(id);
        List<RecallContact> found = contacts.findByRecallId(id);
        List<UUID> ids = found.stream().map(RecallContact::getId).toList();
        LocalDate today = today();
        List<RecallLine> lines = traceability.recallScope(batchIds(id), permissions).lines();
        return new RecallDashboard(
                found.size(),
                count(found, RecallContact.Status.PENDING),
                attempts.findByContactRecordIdInOrderByAttemptedAt(ids).size(),
                (int) found.stream().filter(contact -> REACHED.contains(contact.getStatus())).count(),
                (int) acknowledgements.findByContactRecordIdInOrderByAcknowledgedOn(ids).stream()
                        .map(RecallAcknowledgement::getContactRecordId).distinct().count(),
                count(found, RecallContact.Status.UNREACHABLE),
                count(found, RecallContact.Status.RETURN_IN_PROGRESS),
                count(found, RecallContact.Status.RESOLVED),
                (int) found.stream()
                        .filter(contact -> !contact.isSettled() && contact.getFollowUpOn() != null
                                && !contact.getFollowUpOn().isAfter(today))
                        .count(),
                sum(lines, RecallLine::quantity),
                sum(lines, RecallLine::returnedQuantity),
                sum(lines, RecallLine::outstandingQuantity));
    }

    @Transactional
    public RecallDetail requestClosure(UUID id, RequestClosureRequest request, Set<String> permissions) {
        Recall recall = lock(id);
        recall.requireOpen();
        ClosureReview review = review(recall, syncedScope(recall, permissions), request.accepted());
        if (!review.blockers().isEmpty()) {
            throw new BusinessRuleException("This recall cannot close yet: " + String.join(" ", review.blockers()));
        }
        recall.requestClosure(TenantContext.getCurrentUserId(), request.note().trim(), Instant.now());
        recalls.flush();
        audit.record("RECALL_CLOSURE_REQUESTED", ENTITY, id);
        return detail(recall, permissions);
    }

    @Transactional
    public RecallDetail sendBack(UUID id, String reason, Set<String> permissions) {
        Recall recall = lock(id);
        recall.sendBack();
        recalls.flush();
        audit.record("RECALL_CLOSURE_SENT_BACK", ENTITY, id, null, Map.of("reason", reason));
        return detail(recall, permissions);
    }

    /** Looks again at everything, because goods may have gone out to someone new since closure was requested. */
    @Transactional
    public RecallDetail close(UUID id, Set<String> permissions) {
        Recall recall = lock(id);
        ClosureReview review = review(recall, syncedScope(recall, permissions), true);
        if (!review.blockers().isEmpty()) {
            throw new BusinessRuleException("This recall cannot close yet: " + String.join(" ", review.blockers()));
        }
        recall.close(TenantContext.getCurrentUserId(), Instant.now());
        recalls.flush();
        audit.record("RECALL_CLOSED", ENTITY, id);
        return detail(recall, permissions);
    }

    @Transactional(readOnly = true)
    public RecallReport report(UUID id, Set<String> permissions) {
        Recall recall = load(id);
        RecallScope covered = traceability.recallScope(batchIds(id), permissions);
        OrganizationResponse organization = organizations.getCurrent();
        ZoneId zone = ZoneId.of(organization.timezone());
        Instant now = Instant.now();
        List<ContactView> views = views(recall, covered);
        Map<UUID, CustomerResponse> known = dispatches.customersById(
                views.stream().map(ContactView::customerId).toList());
        List<ReportCustomer> customers = views.stream().map(contact -> {
            CustomerResponse customer = known.get(contact.customerId());
            return new ReportCustomer(contact.customerCode(), contact.customerName(),
                    Optional.ofNullable(contact.contactPerson()).orElse(customer.contactPerson()),
                    customer.email(), customer.phone(), customer.address(), contact.status(), contact.assignedName(),
                    contact.attempts(), contact.acknowledgedOn(), contact.exceptionReason(), contact.quantity(),
                    contact.returnedQuantity(), contact.outstandingQuantity());
        }).toList();
        return new RecallReport(organization.name(), organization.legalName(), organization.address(),
                organization.gstin(), now,
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(zone).format(now),
                detail(recall, covered), products(covered), customers, covered.lines(), covered.serialNumbers(),
                covered.inspections(), covered.ncrs(), historyOf(contacts.findByRecallId(id)));
    }

    private static List<ReportProduct> products(RecallScope covered) {
        return covered.batches().stream()
                .map(batch -> new ReportProduct(batch.productCode(), batch.productName(), batch.batchNumber(), batch.status()))
                .toList();
    }

    private RecallScope syncedScope(Recall recall, Set<String> permissions) {
        RecallScope covered = traceability.recallScope(batchIds(recall.getId()), permissions);
        addMissingCustomers(recall, covered);
        return covered;
    }

    /** A batch can be dispatched after its recall starts. Whoever received it must be contacted too. */
    private void addMissingCustomers(Recall recall, RecallScope covered) {
        Set<UUID> present = contacts.findByRecallId(recall.getId()).stream()
                .map(RecallContact::getCustomerId).collect(Collectors.toSet());
        Set<UUID> missing = covered.lines().stream().map(RecallLine::customerId)
                .filter(id -> !present.contains(id)).collect(Collectors.toSet());
        dispatches.customersById(missing).values().forEach(customer -> contacts.saveAndFlush(new RecallContact(
                recall.getId(), customer.id(), customer.contactPerson(),
                Stream.of(customer.email(), customer.phone()).filter(Objects::nonNull).collect(Collectors.joining(", ")))));
    }

    private RecallDetail detail(Recall recall, Set<String> permissions) {
        return detail(recall, traceability.recallScope(batchIds(recall.getId()), permissions));
    }

    private RecallDetail detail(Recall recall, RecallScope covered) {
        Set<UUID> roots = batchIds(recall.getId());
        List<String> names = production.views(roots).stream().map(ProductionBatchView::batchNumber).sorted().toList();
        return new RecallDetail(recall.getId(), recall.getRecallNumber(), recall.getRecallDate(), recall.getReason(),
                recall.getInstructions(), recall.getCapaId(), recall.getStatus().name(), names, recall.getClosureNote(),
                memberName(recall.getClosureRequestedBy()), recall.getClosureRequestedAt(),
                memberName(recall.getClosedBy()), recall.getClosedAt(), review(recall, covered, true));
    }

    private ClosureReview review(Recall recall, RecallScope covered, boolean outstandingAccepted) {
        List<RecallContact> found = contacts.findByRecallId(recall.getId());
        Set<UUID> present = found.stream().map(RecallContact::getCustomerId).collect(Collectors.toSet());
        Map<UUID, CustomerResponse> known = dispatches.customersById(present);
        List<String> unresolved = found.stream().filter(contact -> !contact.isSettled())
                .map(contact -> known.get(contact.getCustomerId()).name()).sorted().toList();
        BigDecimal outstanding = sum(covered.lines(), RecallLine::outstandingQuantity);
        List<String> blockers = new ArrayList<>();
        if (!unresolved.isEmpty()) {
            blockers.add("Customers still unresolved: " + String.join(", ", unresolved) + ".");
        }
        if (outstanding.signum() > 0 && !outstandingAccepted) {
            blockers.add("Goods remain with customers. Confirm this and explain it in the note.");
        }
        Optional<Boolean> capaClosed = Optional.ofNullable(recall.getCapaId()).flatMap(capas::isClosed);
        if (capaClosed.isPresent() && !capaClosed.get()) {
            blockers.add("The linked CAPA is not closed.");
        }
        List<UUID> ids = found.stream().map(RecallContact::getId).toList();
        return new ClosureReview(found.size(),
                (int) found.stream().filter(contact -> REACHED.contains(contact.getStatus())).count(),
                (int) acknowledgements.findByContactRecordIdInOrderByAcknowledgedOn(ids).stream()
                        .map(RecallAcknowledgement::getContactRecordId).distinct().count(),
                (int) found.stream().filter(contact -> contact.getExceptionAt() != null).count(),
                unresolved,
                sum(covered.lines(), RecallLine::quantity),
                sum(covered.lines(), RecallLine::returnedQuantity),
                outstanding,
                capaClosed.map(closed -> closed ? "CLOSED" : "OPEN").orElse(null),
                blockers);
    }

    private ContactView view(UUID id, UUID customerId, Set<String> permissions) {
        return views(load(id), traceability.recallScope(batchIds(id), permissions)).stream()
                .filter(contact -> contact.customerId().equals(customerId))
                .findFirst().orElseThrow();
    }

    private List<ContactView> views(Recall recall, RecallScope covered) {
        List<RecallContact> found = contacts.findByRecallId(recall.getId());
        List<UUID> ids = found.stream().map(RecallContact::getId).toList();
        Map<UUID, List<RecallContactAttempt>> tried = attempts.findByContactRecordIdInOrderByAttemptedAt(ids).stream()
                .collect(Collectors.groupingBy(RecallContactAttempt::getContactRecordId));
        Map<UUID, LocalDate> acknowledged = new HashMap<>();
        acknowledgements.findByContactRecordIdInOrderByAcknowledgedOn(ids)
                .forEach(ack -> acknowledged.put(ack.getContactRecordId(), ack.getAcknowledgedOn()));
        Map<UUID, CustomerResponse> known = dispatches.customersById(
                found.stream().map(RecallContact::getCustomerId).toList());
        Map<UUID, List<RecallLine>> lines = covered.lines().stream().collect(Collectors.groupingBy(RecallLine::customerId));
        return found.stream().map(contact -> {
            CustomerResponse customer = known.get(contact.getCustomerId());
            List<RecallLine> theirs = lines.getOrDefault(contact.getCustomerId(), List.of());
            List<RecallContactAttempt> theirAttempts = tried.getOrDefault(contact.getId(), List.of());
            return new ContactView(contact.getId(), contact.getCustomerId(), customer.code(), customer.name(),
                    contact.getStatus().name(), contact.getContactPerson(), contact.getContactDetails(),
                    contact.getAssignedTo(), memberName(contact.getAssignedTo()), contact.getFollowUpOn(),
                    contact.getNotes(), contact.getExceptionReason(), theirAttempts.size(),
                    theirAttempts.isEmpty() ? null : theirAttempts.getLast().getAttemptedAt(),
                    acknowledged.get(contact.getId()),
                    sum(theirs, RecallLine::quantity), sum(theirs, RecallLine::returnedQuantity),
                    sum(theirs, RecallLine::outstandingQuantity));
        }).sorted(Comparator.comparing(ContactView::customerName)).toList();
    }

    private List<HistoryEntry> historyOf(List<RecallContact> found) {
        Map<UUID, RecallContact> byId = found.stream().collect(Collectors.toMap(RecallContact::getId, Function.identity()));
        Map<UUID, CustomerResponse> known = dispatches.customersById(
                found.stream().map(RecallContact::getCustomerId).toList());
        ZoneId zone = ZoneId.of(organizations.getCurrent().timezone());
        List<HistoryEntry> entries = new ArrayList<>();
        attempts.findByContactRecordIdInOrderByAttemptedAt(byId.keySet()).forEach(attempt -> {
            UUID customer = byId.get(attempt.getContactRecordId()).getCustomerId();
            entries.add(new HistoryEntry(attempt.getId(), "ATTEMPT", customer, known.get(customer).name(),
                    attempt.getMethod().name(), attempt.getContactPerson(), attempt.getContactDetails(),
                    attempt.getAttemptedAt(), attempt.getAttemptedAt().atZone(zone).toLocalDate(),
                    attempt.getOutcome().name(), attempt.getCustomerResponse(),
                    attempt.getNotes(), memberName(attempt.getCreatedBy())));
        });
        acknowledgements.findByContactRecordIdInOrderByAcknowledgedOn(byId.keySet()).forEach(ack -> {
            UUID customer = byId.get(ack.getContactRecordId()).getCustomerId();
            entries.add(new HistoryEntry(ack.getId(), "ACKNOWLEDGEMENT", customer, known.get(customer).name(), null,
                    // Only the day is known, and it follows whatever attempts led to it that day.
                    ack.getAcknowledgedBy(), null, ack.getAcknowledgedOn().atTime(LocalTime.MAX).atZone(zone).toInstant(),
                    ack.getAcknowledgedOn(), null, ack.getCustomerResponse(), null, memberName(ack.getCreatedBy())));
        });
        entries.sort(Comparator.comparing(HistoryEntry::at));
        return entries;
    }

    private RecallContact editable(UUID id, UUID customerId) {
        lock(id).requireOpen();
        return contacts.findByRecallIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Recall customer", customerId.toString()));
    }

    private Recall lock(UUID id) {
        load(id);
        return recalls.findForUpdate(id).orElseThrow();
    }

    /** Every read and write comes through here, so that facility limits apply to all of them. */
    private Recall load(UUID id) {
        Recall recall = recalls.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id.toString()));
        scope.require(facilitiesOf(batchIds(id)), ENTITY, id.toString());
        return recall;
    }

    private Set<UUID> batchIds(UUID id) {
        return batches.findByRecallIdIn(List.of(id)).stream()
                .map(RecallBatch::getProductionBatchId).collect(Collectors.toSet());
    }

    private Set<UUID> facilitiesOf(Collection<UUID> ids) {
        return production.views(ids).stream().map(ProductionBatchView::facilityId).collect(Collectors.toSet());
    }

    private String memberName(UUID userId) {
        return userId == null ? null : access.memberName(userId).orElse(null);
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    private static int count(List<RecallContact> found, RecallContact.Status status) {
        return (int) found.stream().filter(contact -> contact.getStatus() == status).count();
    }

    private static BigDecimal sum(List<RecallLine> lines, Function<RecallLine, BigDecimal> value) {
        return lines.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.RECALL_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.RECALL_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return contacts.findById(entityId)
                .filter(contact -> scope.restriction()
                        .map(allowed -> allowed.containsAll(facilitiesOf(batchIds(contact.getRecallId()))))
                        .orElse(true))
                .isPresent();
    }

    @Override
    public boolean locked(UUID entityId) {
        return contacts.findById(entityId)
                .flatMap(contact -> recalls.findById(contact.getRecallId()))
                .map(recall -> recall.getStatus() == Recall.Status.CLOSED)
                .orElse(false);
    }
}
