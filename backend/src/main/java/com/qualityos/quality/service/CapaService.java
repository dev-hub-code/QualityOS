package com.qualityos.quality.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.quality.dto.QualityDtos.ActionResponse;
import com.qualityos.quality.dto.QualityDtos.AddActionRequest;
import com.qualityos.quality.dto.QualityDtos.CapaDetail;
import com.qualityos.quality.dto.QualityDtos.CapaSummary;
import com.qualityos.quality.dto.QualityDtos.CreateCapaRequest;
import com.qualityos.quality.dto.QualityDtos.SaveAnalysisRequest;
import com.qualityos.quality.dto.QualityDtos.StatusChange;
import com.qualityos.quality.dto.QualityDtos.VerificationResponse;
import com.qualityos.quality.dto.QualityDtos.VerifyRequest;
import com.qualityos.quality.entity.CapaAction;
import com.qualityos.quality.entity.CapaStatusChange;
import com.qualityos.quality.entity.CapaVerification;
import com.qualityos.quality.entity.CorrectiveAction;
import com.qualityos.quality.entity.NonConformance;
import com.qualityos.quality.repository.CapaActionRepository;
import com.qualityos.quality.repository.CapaStatusChangeRepository;
import com.qualityos.quality.repository.CapaVerificationRepository;
import com.qualityos.quality.repository.CorrectiveActionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CapaService implements AttachmentPolicy {

    private static final String ENTITY = "CorrectiveAction";
    private static final String ATTACHMENT_TYPE = "CAPA";
    private static final String NUMBER_PREFIX = "CAPA";
    private static final Duration OVERDUE_REMINDER_INTERVAL = Duration.ofDays(7);
    private static final int DUE_SOON_DAYS = 3;

    private final CorrectiveActionRepository capas;
    private final CapaActionRepository actions;
    private final CapaVerificationRepository verifications;
    private final CapaStatusChangeRepository history;
    private final NcrService ncrs;
    private final TenantAccessService access;
    private final OrganizationService organizations;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final QualityAlerts alerts;
    private final JsonMapper json;

    CapaService(
            CorrectiveActionRepository capas,
            CapaActionRepository actions,
            CapaVerificationRepository verifications,
            CapaStatusChangeRepository history,
            NcrService ncrs,
            TenantAccessService access,
            OrganizationService organizations,
            DocumentNumberService numbers,
            AuditService audit,
            QualityAlerts alerts,
            JsonMapper json) {
        this.capas = capas;
        this.actions = actions;
        this.verifications = verifications;
        this.history = history;
        this.ncrs = ncrs;
        this.access = access;
        this.organizations = organizations;
        this.numbers = numbers;
        this.audit = audit;
        this.alerts = alerts;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<CapaSummary> list(
            String search, CorrectiveAction.Status status, UUID ncrId, Pageable pageable) {
        Specification<CorrectiveAction> filter = Specification
                .<CorrectiveAction>where(Searches.contains(search, "capaNumber", "title"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("ncrId", ncrId));
        Page<CorrectiveAction> page = capas.findAll(filter, pageable);
        Map<UUID, String> members = access.activeMembers();
        LocalDate today = today();
        return PageResponse.from(page, capa -> new CapaSummary(
                capa.getId(),
                capa.getCapaNumber(),
                capa.getTitle(),
                capa.getStatus().name(),
                members.get(capa.getOwnerId()),
                capa.getTargetDate(),
                isOverdue(capa, today)));
    }

    @Transactional(readOnly = true)
    public CapaDetail get(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public CapaDetail create(CreateCapaRequest request) {
        ncrs.requireMember(request.ownerId());
        boolean effectiveness = request.effectivenessRequired();
        if (request.ncrId() != null) {
            NonConformance ncr = ncrs.requireOpen(request.ncrId());
            effectiveness |= ncr.getSeverity() != NonConformance.Severity.MINOR;
        }
        CorrectiveAction capa = capas.saveAndFlush(new CorrectiveAction(
                numbers.next(NUMBER_PREFIX),
                request.ncrId(),
                request.title().trim(),
                request.problemStatement().trim(),
                request.ownerId(),
                request.targetDate(),
                effectiveness));
        history.save(new CapaStatusChange(capa.getId(), null, capa.getStatus().name(), null));
        audit.record("CAPA_CREATED", ENTITY, capa.getId(), null, Map.of("number", capa.getCapaNumber()));
        alerts.capaAssigned(capa);
        return toDetail(capa);
    }

    @Transactional
    public CapaDetail startAnalysis(UUID id) {
        return transition(id, null, CorrectiveAction::startAnalysis);
    }

    @Transactional
    public CapaDetail saveAnalysis(UUID id, SaveAnalysisRequest request) {
        CorrectiveAction capa = lock(id);
        capa.saveAnalysis(
                request.rootCause().trim(),
                json.writeValueAsString(request.fiveWhys()),
                json.writeValueAsString(request.fishbone()));
        capas.saveAndFlush(capa);
        audit.record("CAPA_ANALYSIS_SAVED", ENTITY, id);
        return toDetail(capa);
    }

    @Transactional
    public CapaDetail finishAnalysis(UUID id) {
        return transition(id, null, CorrectiveAction::finishAnalysis);
    }

    @Transactional
    public CapaDetail addAction(UUID id, AddActionRequest request) {
        CorrectiveAction capa = lock(id);
        capa.requirePlanning();
        ncrs.requireMember(request.ownerId());
        CapaAction action = actions.saveAndFlush(new CapaAction(
                id, request.actionType(), request.description().trim(), request.ownerId(), request.targetDate()));
        audit.record("CAPA_ACTION_ADDED", ENTITY, id, null, Map.of("action", action.getId()));
        alerts.actionAssigned(capa, action);
        return toDetail(capa);
    }

    /** After an ineffective verification, this needs a new action: repeating the same work is not a new plan. */
    @Transactional
    public CapaDetail startImplementation(UUID id) {
        CorrectiveAction capa = lock(id);
        List<CapaAction> planned = actions.findByCapaIdOrderByCreatedAt(id);
        boolean corrective = planned.stream()
                .anyMatch(action -> !action.isDone() && action.getActionType() == CapaAction.Type.CORRECTIVE);
        if (!corrective) {
            throw new BusinessRuleException("Add a corrective action first.");
        }
        return apply(capa, null, CorrectiveAction::startImplementation);
    }

    @Transactional
    public CapaDetail completeAction(UUID id, UUID actionId, String note) {
        CorrectiveAction capa = lock(id);
        capa.requireImplementation();
        actions.findByIdAndCapaId(actionId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Action", actionId))
                .complete(note.trim(), Instant.now());
        audit.record("CAPA_ACTION_COMPLETED", ENTITY, id, null, Map.of("action", actionId));
        return toDetail(capa);
    }

    @Transactional
    public CapaDetail submitForVerification(UUID id) {
        CorrectiveAction capa = lock(id);
        if (actions.findByCapaIdOrderByCreatedAt(id).stream().anyMatch(action -> !action.isDone())) {
            throw new BusinessRuleException("Every action must be complete first.");
        }
        CapaDetail detail = apply(capa, null, CorrectiveAction::submitForVerification);
        alerts.capaAwaitingVerification(capa);
        return detail;
    }

    @Transactional
    public CapaDetail verify(UUID id, VerifyRequest request) {
        CorrectiveAction capa = lock(id);
        capa.requireVerifiableBy(TenantContext.getCurrentUserId());
        verifications.saveAndFlush(new CapaVerification(
                id, request.effective(), request.method().trim(), request.note().trim()));
        audit.record("CAPA_VERIFIED", ENTITY, id, null, Map.of("effective", request.effective()));
        if (!request.effective()) {
            CapaDetail detail = apply(capa, "Not effective: " + request.note().trim(), CorrectiveAction::returnToPlanning);
            alerts.capaAssigned(capa);
            return detail;
        }
        return toDetail(capa);
    }

    @Transactional
    public CapaDetail close(UUID id, String note) {
        CorrectiveAction capa = lock(id);
        List<CapaVerification> recorded = verifications.findByCapaIdOrderByCreatedAt(id);
        // The newest verdict is the one that counts, so an earlier pass cannot outlive a later failure.
        boolean verified = !recorded.isEmpty() && recorded.getLast().isEffective();
        if (capa.isEffectivenessRequired() && !verified) {
            throw new BusinessRuleException("This CAPA needs a successful effectiveness verification.");
        }
        CapaDetail closed = apply(capa, note, closing -> closing.close(TenantContext.getCurrentUserId(), note, Instant.now()));
        alerts.capaClosed(capa);
        return closed;
    }

    @Transactional
    public CapaDetail cancel(UUID id, String reason) {
        return transition(id, reason, capa -> capa.cancel(reason));
    }

    /** Reminds each owner when a CAPA goes overdue, and weekly after that. Runs under the current tenant. */
    @Transactional
    public int remindOverdue() {
        Instant now = Instant.now();
        List<CorrectiveAction> overdue = capas.findOverdue(today(), now.minus(OVERDUE_REMINDER_INTERVAL));
        overdue.forEach(capa -> {
            alerts.capaOverdue(capa);
            capa.setOverdueNotifiedAt(now);
        });
        return overdue.size();
    }

    /** Reminds each owner once, shortly before the date. Runs under the current tenant. */
    @Transactional
    public int remindDueSoon() {
        Instant now = Instant.now();
        List<CorrectiveAction> due = capas.findDueSoon(today(), today().plusDays(DUE_SOON_DAYS));
        due.forEach(capa -> {
            alerts.capaDueSoon(capa);
            capa.setDueRemindedAt(now);
        });
        return due.size();
    }

    /** @return whether this CAPA has been closed, or empty when it does not exist */
    @Transactional(readOnly = true)
    public Optional<Boolean> isClosed(UUID id) {
        return capas.findById(id).map(capa -> capa.getStatus() == CorrectiveAction.Status.CLOSED);
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.CAPA_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.CAPA_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return capas.existsById(entityId);
    }

    @Override
    public boolean locked(UUID entityId) {
        return capas.findById(entityId).map(capa -> capa.getStatus().isFinished()).orElse(false);
    }

    private CapaDetail transition(UUID id, String note, Consumer<CorrectiveAction> change) {
        return apply(lock(id), note, change);
    }

    private CapaDetail apply(CorrectiveAction capa, String note, Consumer<CorrectiveAction> change) {
        CorrectiveAction.Status previous = capa.getStatus();
        change.accept(capa);
        capas.saveAndFlush(capa);
        history.save(new CapaStatusChange(capa.getId(), previous.name(), capa.getStatus().name(), note));
        audit.record("CAPA_STATUS_CHANGED", ENTITY, capa.getId(),
                Map.of("status", previous), Map.of("status", capa.getStatus()));
        return toDetail(capa);
    }

    private CorrectiveAction load(UUID id) {
        return capas.findById(id).orElseThrow(() -> new ResourceNotFoundException("CAPA", id));
    }

    private CorrectiveAction lock(UUID id) {
        return capas.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("CAPA", id));
    }

    /** Dates are the customer's: a CAPA due today is not overdue until the day ends at their plant. */
    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    private static boolean isOverdue(CorrectiveAction capa, LocalDate today) {
        return !capa.getStatus().isFinished() && capa.getTargetDate().isBefore(today);
    }

    private CapaDetail toDetail(CorrectiveAction capa) {
        Map<UUID, String> members = access.activeMembers();
        String formerMember = "System or former member";
        List<ActionResponse> planned = actions.findByCapaIdOrderByCreatedAt(capa.getId()).stream()
                .map(action -> new ActionResponse(
                        action.getId(),
                        action.getActionType().name(),
                        action.getDescription(),
                        action.getOwnerId(),
                        members.get(action.getOwnerId()),
                        action.getTargetDate(),
                        action.getCompletedAt(),
                        action.getCompletionNote()))
                .toList();
        List<VerificationResponse> verdicts = verifications.findByCapaIdOrderByCreatedAt(capa.getId()).stream()
                .map(verdict -> new VerificationResponse(verdict.isEffective(), verdict.getMethod(), verdict.getNote(),
                        members.getOrDefault(verdict.getCreatedBy(), formerMember), verdict.getCreatedAt()))
                .toList();
        List<StatusChange> changes = history.findByCapaIdOrderByCreatedAt(capa.getId()).stream()
                .map(change -> new StatusChange(change.getFromStatus(), change.getToStatus(), change.getNote(),
                        members.getOrDefault(change.getCreatedBy(), formerMember), change.getCreatedAt()))
                .toList();
        return new CapaDetail(
                capa.getId(),
                capa.getCapaNumber(),
                capa.getTitle(),
                capa.getProblemStatement(),
                capa.getStatus().name(),
                capa.getNcrId(),
                capa.getNcrId() == null ? null : ncrs.get(capa.getNcrId()).ncrNumber(),
                capa.getRootCause(),
                json.readValue(capa.getFiveWhys(), new TypeReference<List<String>>() { }),
                json.readValue(capa.getFishbone(), new TypeReference<Map<String, List<String>>>() { }),
                capa.getOwnerId(),
                members.get(capa.getOwnerId()),
                capa.getTargetDate(),
                isOverdue(capa, today()),
                capa.isEffectivenessRequired(),
                capa.getOutcomeNote(),
                capa.getClosedAt(),
                planned,
                verdicts,
                changes);
    }
}
