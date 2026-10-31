package com.qualityos.auditmanagement.service;

import com.qualityos.auditmanagement.dto.AuditDtos.AnswerItemRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditDetail;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditReport;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditReview;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditSummary;
import com.qualityos.auditmanagement.dto.AuditDtos.CreateAuditRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.FindingRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.FindingView;
import com.qualityos.auditmanagement.dto.AuditDtos.ItemView;
import com.qualityos.auditmanagement.dto.AuditDtos.ReplanAuditRequest;
import com.qualityos.auditmanagement.entity.AuditChecklistItem;
import com.qualityos.auditmanagement.entity.AuditFinding;
import com.qualityos.auditmanagement.entity.AuditItem;
import com.qualityos.auditmanagement.entity.QualityAudit;
import com.qualityos.auditmanagement.repository.AuditFindingRepository;
import com.qualityos.auditmanagement.repository.AuditItemRepository;
import com.qualityos.auditmanagement.repository.QualityAuditRepository;
import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.product.service.ProductService;
import com.qualityos.quality.dto.QualityDtos.AuditFailure;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
import com.qualityos.quality.service.NcrService;
import com.qualityos.supplier.service.SupplierService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualityAuditService implements AttachmentPolicy {

    private static final String ENTITY = "QualityAudit";
    private static final String RESOURCE = "Audit";
    private static final String ATTACHMENT_TYPE = "AUDIT_FINDING";
    private static final String NUMBER_PREFIX = "AUD";

    private final QualityAuditRepository audits;
    private final AuditItemRepository items;
    private final AuditFindingRepository findings;
    private final AuditChecklistService checklists;
    private final NcrService ncrs;
    private final FacilityService facilities;
    private final SupplierService suppliers;
    private final ProductService products;
    private final OrganizationService organizations;
    private final TenantAccessService access;
    private final FacilityScope scope;
    private final NotificationService notifications;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    QualityAuditService(
            QualityAuditRepository audits,
            AuditItemRepository items,
            AuditFindingRepository findings,
            AuditChecklistService checklists,
            NcrService ncrs,
            FacilityService facilities,
            SupplierService suppliers,
            ProductService products,
            OrganizationService organizations,
            TenantAccessService access,
            FacilityScope scope,
            NotificationService notifications,
            DocumentNumberService numbers,
            AuditService audit) {
        this.audits = audits;
        this.items = items;
        this.findings = findings;
        this.checklists = checklists;
        this.ncrs = ncrs;
        this.facilities = facilities;
        this.suppliers = suppliers;
        this.products = products;
        this.organizations = organizations;
        this.access = access;
        this.scope = scope;
        this.notifications = notifications;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditSummary> list(
            String search, QualityAudit.Status status, QualityAudit.Type type, UUID facilityId, Pageable pageable) {
        Specification<QualityAudit> filter = Specification
                .<QualityAudit>where(Searches.contains(search, "auditNumber", "title"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("auditType", type))
                .and(Searches.equalTo("facilityId", facilityId));
        Optional<Set<UUID>> restriction = scope.restriction();
        if (restriction.isPresent()) {
            filter = filter.and((root, query, cb) -> root.get("facilityId").in(restriction.get()));
        }
        Map<UUID, String> members = access.activeMembers();
        LocalDate today = today();
        return PageResponse.from(audits.findAll(filter, pageable), row -> new AuditSummary(
                row.getId(),
                row.getAuditNumber(),
                row.getAuditType().name(),
                row.getTitle(),
                row.getStatus().name(),
                row.getPlannedOn(),
                members.get(row.getLeadAuditorId()),
                row.getStatus() == QualityAudit.Status.PLANNED && row.getPlannedOn().isBefore(today)));
    }

    @Transactional(readOnly = true)
    public AuditDetail get(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public AuditDetail create(CreateAuditRequest request) {
        facilities.requireActive(request.facilityId());
        scope.require(Set.of(request.facilityId()), "Facility", request.facilityId().toString());
        requireMember(request.leadAuditorId());
        if (request.auditType() == QualityAudit.Type.SUPPLIER && request.supplierId() == null) {
            throw new BusinessRuleException("A supplier audit needs a supplier.");
        }
        if (request.auditType() == QualityAudit.Type.PRODUCT && request.productId() == null) {
            throw new BusinessRuleException("A product audit needs a product.");
        }
        if (request.supplierId() != null) {
            suppliers.reference(request.supplierId());
        }
        if (request.productId() != null) {
            products.requireExists(request.productId());
        }
        List<AuditChecklistItem> questions = checklists.questions(request.checklistId());
        QualityAudit created = audits.saveAndFlush(new QualityAudit(
                numbers.next(NUMBER_PREFIX),
                request.auditType(),
                request.title().trim(),
                request.scope().trim(),
                request.facilityId(),
                request.supplierId(),
                request.productId(),
                request.checklistId(),
                request.plannedOn(),
                request.leadAuditorId()));
        questions.forEach(question -> items.save(new AuditItem(
                created.getId(), question.getPosition(), question.getQuestion(), question.getClauseReference())));
        audit.record("AUDIT_PLANNED", ENTITY, created.getId());
        tellLead(created);
        return toDetail(created);
    }

    @Transactional
    public AuditDetail replan(UUID id, ReplanAuditRequest request) {
        QualityAudit row = load(id);
        requireMember(request.leadAuditorId());
        boolean told = request.leadAuditorId().equals(row.getLeadAuditorId())
                && request.plannedOn().equals(row.getPlannedOn());
        row.replan(request.title().trim(), request.scope().trim(), request.plannedOn(), request.leadAuditorId());
        audit.record("AUDIT_REPLANNED", ENTITY, id);
        if (!told) {
            tellLead(row);
        }
        return toDetail(row);
    }

    @Transactional
    public AuditDetail start(UUID id) {
        QualityAudit row = load(id);
        row.start(Instant.now());
        audit.record("AUDIT_STARTED", ENTITY, id);
        return toDetail(row);
    }

    @Transactional
    public AuditDetail answer(UUID id, UUID itemId, AnswerItemRequest request) {
        QualityAudit row = load(id);
        row.requireInProgress();
        AuditItem item = items.findByIdAndAuditId(itemId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit item", itemId.toString()));
        item.answer(request.result(), request.note());
        return toDetail(row);
    }

    /** A nonconformity raises its NCR in the same transaction, so that one cannot be recorded and then forgotten. */
    @Transactional
    public AuditDetail addFinding(UUID id, FindingRequest request) {
        QualityAudit row = load(id);
        row.requireInProgress();
        if (request.auditItemId() != null && items.findByIdAndAuditId(request.auditItemId(), id).isEmpty()) {
            throw new ResourceNotFoundException("Audit item", request.auditItemId().toString());
        }
        AuditFinding finding = findings.saveAndFlush(new AuditFinding(id, request.auditItemId(), request.grade(),
                request.statement().trim(), request.evidence().trim(), request.clauseReference()));
        audit.record("AUDIT_FINDING_RECORDED", ENTITY, id, null, Map.of("grade", request.grade().name()));
        if (request.grade().isNonconformity()) {
            raise(row, finding);
        }
        return toDetail(row);
    }

    /** For an observation someone decides to act on. Repeats return the same NCR. */
    @Transactional
    public AuditDetail raiseNcr(UUID id, UUID findingId) {
        QualityAudit row = load(id);
        if (row.isFinished() || row.getStatus() == QualityAudit.Status.PENDING_CLOSURE) {
            throw new BusinessRuleException("This audit is no longer open for changes.");
        }
        AuditFinding finding = findings.findByIdAndAuditId(findingId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Audit finding", findingId.toString()));
        if (finding.getNcrId() == null) {
            raise(row, finding);
        }
        return toDetail(row);
    }

    @Transactional
    public AuditDetail complete(UUID id, String summary) {
        QualityAudit row = load(id);
        List<AuditItem> questions = items.findByAuditIdOrderByPosition(id);
        List<AuditFinding> recorded = findings.findByAuditIdOrderByCreatedAt(id);
        long unanswered = questions.stream().filter(item -> item.getResult() == null).count();
        if (unanswered > 0) {
            throw new BusinessRuleException("%d checklist question(s) have no answer.".formatted(unanswered));
        }
        Set<UUID> explained = recorded.stream().map(AuditFinding::getAuditItemId).collect(Collectors.toSet());
        questions.stream()
                .filter(item -> item.getResult() == AuditItem.Result.NONCONFORMING)
                .filter(item -> !explained.contains(item.getId()))
                .findFirst()
                .ifPresent(item -> {
                    throw new BusinessRuleException(
                            "Question %d is nonconforming and needs a finding.".formatted(item.getPosition()));
                });
        row.complete(summary.trim(), Instant.now());
        audit.record("AUDIT_COMPLETED", ENTITY, id);
        if (!recorded.isEmpty()) {
            Set<UUID> people = new HashSet<>(access.holdersOf(Permissions.AUDIT_CLOSE));
            people.add(row.getLeadAuditorId());
            notifications.notify(scope.allowedIn(row.getFacilityId(), people), NotificationType.AUDIT_FINDINGS,
                    "%s has %d finding(s)".formatted(row.getAuditNumber(), recorded.size()), row.getTitle(), link(row),
                    new EmailContent(EmailTemplate.AUDIT_FINDINGS, Map.of("auditNumber", row.getAuditNumber())));
        }
        return toDetail(row);
    }

    @Transactional
    public AuditDetail requestClosure(UUID id, String note) {
        QualityAudit row = load(id);
        requireNoBlockers(row);
        row.requestClosure(TenantContext.getCurrentUserId(), note.trim(), Instant.now());
        audit.record("AUDIT_CLOSURE_REQUESTED", ENTITY, id);
        notifications.notify(scope.allowedIn(row.getFacilityId(), access.holdersOf(Permissions.AUDIT_CLOSE)),
                NotificationType.AUDIT_AWAITING_CLOSURE, row.getAuditNumber() + " is ready for closure", row.getTitle(),
                link(row));
        return toDetail(row);
    }

    @Transactional
    public AuditDetail sendBack(UUID id, String reason) {
        QualityAudit row = load(id);
        row.sendBack();
        audit.record("AUDIT_CLOSURE_SENT_BACK", ENTITY, id, null, Map.of("reason", reason.trim()));
        return toDetail(row);
    }

    @Transactional
    public AuditDetail close(UUID id) {
        QualityAudit row = load(id);
        requireNoBlockers(row);
        row.close(TenantContext.getCurrentUserId(), Instant.now());
        audit.record("AUDIT_CLOSED", ENTITY, id);
        return toDetail(row);
    }

    @Transactional
    public AuditDetail cancel(UUID id, String reason) {
        QualityAudit row = load(id);
        if (!findings.findByAuditIdOrderByCreatedAt(id).isEmpty()) {
            throw new BusinessRuleException("An audit with findings cannot be cancelled.");
        }
        row.cancel(reason.trim());
        audit.record("AUDIT_CANCELLED", ENTITY, id, null, Map.of("reason", reason.trim()));
        return toDetail(row);
    }

    @Transactional(readOnly = true)
    public AuditReport report(UUID id) {
        AuditDetail detail = toDetail(load(id));
        OrganizationResponse organization = organizations.getCurrent();
        ZoneId zone = ZoneId.of(organization.timezone());
        return new AuditReport(organization.name(), organization.legalName(), organization.address(),
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(zone).format(Instant.now()), detail);
    }

    private void raise(QualityAudit row, AuditFinding finding) {
        UUID ncr = ncrs.raiseFromAudit(new AuditFailure(
                row.getId(),
                finding.getId(),
                row.getAuditNumber(),
                row.getSupplierId(),
                finding.getGrade() == AuditFinding.Grade.MAJOR_NONCONFORMITY,
                finding.getStatement(),
                finding.getStatement() + "\n\nEvidence: " + finding.getEvidence(),
                row.getFacilityId()));
        finding.setNcrId(ncr);
    }

    private void requireNoBlockers(QualityAudit row) {
        List<String> blockers = blockers(findings.findByAuditIdOrderByCreatedAt(row.getId()), outcomes(row.getId()));
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException(blockers.getFirst());
        }
    }

    private static List<String> blockers(List<AuditFinding> recorded, Map<UUID, NcrOutcome> outcomes) {
        List<String> blockers = new ArrayList<>();
        recorded.stream()
                .map(finding -> outcomes.get(finding.getNcrId()))
                .filter(Objects::nonNull)
                .filter(outcome -> !outcome.finished())
                .forEach(outcome -> blockers.add("%s is still %s.".formatted(
                        outcome.ncrNumber(), outcome.status().replace('_', ' ').toLowerCase())));
        return blockers;
    }

    private Map<UUID, NcrOutcome> outcomes(UUID auditId) {
        return ncrs.outcomes(findings.findByAuditIdOrderByCreatedAt(auditId).stream()
                .map(AuditFinding::getNcrId)
                .filter(Objects::nonNull)
                .toList());
    }

    private void tellLead(QualityAudit row) {
        notifications.notify(Set.of(row.getLeadAuditorId()), NotificationType.AUDIT_SCHEDULED,
                row.getAuditNumber() + " is scheduled", row.getTitle() + " on " + row.getPlannedOn() + ".", link(row),
                new EmailContent(EmailTemplate.AUDIT_SCHEDULED, Map.of(
                        "auditNumber", row.getAuditNumber(), "dueDate", row.getPlannedOn().toString())));
    }

    private void requireMember(UUID userId) {
        if (access.activeMemberContact(userId).isEmpty()) {
            throw new BusinessRuleException("The lead auditor must be an active member of this organisation.");
        }
    }

    private QualityAudit load(UUID id) {
        QualityAudit row = audits.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id.toString()));
        scope.require(Set.of(row.getFacilityId()), RESOURCE, id.toString());
        return row;
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    private static String link(QualityAudit row) {
        return "/audits/" + row.getId();
    }

    private AuditDetail toDetail(QualityAudit row) {
        Map<UUID, String> members = access.activeMembers();
        List<AuditItem> questions = items.findByAuditIdOrderByPosition(row.getId());
        List<AuditFinding> recorded = findings.findByAuditIdOrderByCreatedAt(row.getId());
        Map<UUID, NcrOutcome> outcomes = outcomes(row.getId());
        Map<UUID, Integer> positions =
                questions.stream().collect(Collectors.toMap(AuditItem::getId, AuditItem::getPosition));
        List<FindingView> views = new ArrayList<>();
        for (AuditFinding finding : recorded) {
            views.add(new FindingView(finding.getId(), views.size() + 1, finding.getGrade().name(),
                    finding.getStatement(), finding.getEvidence(), finding.getClauseReference(),
                    positions.get(finding.getAuditItemId()), outcomes.get(finding.getNcrId())));
        }
        return new AuditDetail(
                row.getId(),
                row.getAuditNumber(),
                row.getAuditType().name(),
                row.getTitle(),
                row.getScope(),
                row.getFacilityId(),
                facilities.get(row.getFacilityId()).facility().name(),
                row.getSupplierId(),
                row.getSupplierId() == null ? null : suppliers.reference(row.getSupplierId()).name(),
                row.getProductId(),
                row.getProductId() == null ? null
                        : products.references(Set.of(row.getProductId())).get(row.getProductId()).name(),
                checklists.name(row.getChecklistId()),
                row.getPlannedOn(),
                row.getLeadAuditorId(),
                members.get(row.getLeadAuditorId()),
                row.getStatus().name(),
                row.getSummary(),
                row.getStartedAt(),
                row.getCompletedAt(),
                members.get(row.getClosureRequestedBy()),
                row.getClosureRequestedBy(),
                row.getClosureNote(),
                members.get(row.getClosedBy()),
                row.getClosedAt(),
                row.getCancelReason(),
                questions.stream()
                        .map(item -> new ItemView(item.getId(), item.getPosition(), item.getQuestion(),
                                item.getClauseReference(), item.getResult() == null ? null : item.getResult().name(),
                                item.getNote()))
                        .toList(),
                views,
                new AuditReview(
                        questions.size(),
                        (int) questions.stream().filter(item -> item.getResult() != null).count(),
                        count(questions, AuditItem.Result.CONFORMING),
                        count(questions, AuditItem.Result.NONCONFORMING),
                        count(questions, AuditItem.Result.NOT_APPLICABLE),
                        blockers(recorded, outcomes)));
    }

    private static int count(List<AuditItem> questions, AuditItem.Result result) {
        return (int) questions.stream().filter(item -> item.getResult() == result).count();
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.AUDIT_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.AUDIT_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return findings.findById(entityId)
                .flatMap(finding -> audits.findById(finding.getAuditId()))
                .filter(row -> scope.allows(row.getFacilityId()))
                .isPresent();
    }

    @Override
    public boolean locked(UUID entityId) {
        return findings.findById(entityId)
                .flatMap(finding -> audits.findById(finding.getAuditId()))
                .map(QualityAudit::isFinished)
                .orElse(false);
    }
}
