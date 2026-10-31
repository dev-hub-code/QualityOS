package com.qualityos.calibration.service;

import com.qualityos.calibration.dto.CalibrationDtos.CalibrationResponse;
import com.qualityos.calibration.dto.CalibrationDtos.ChangeStatusRequest;
import com.qualityos.calibration.dto.CalibrationDtos.InstrumentResponse;
import com.qualityos.calibration.dto.CalibrationDtos.RecordCalibrationRequest;
import com.qualityos.calibration.dto.CalibrationDtos.SaveInstrumentRequest;
import com.qualityos.calibration.dto.CalibrationDtos.UsageResponse;
import com.qualityos.calibration.dto.CalibrationDtos.UsedInstrument;
import com.qualityos.calibration.entity.CalibrationRecord;
import com.qualityos.calibration.entity.Instrument;
import com.qualityos.calibration.entity.InstrumentUsage;
import com.qualityos.calibration.repository.CalibrationRecordRepository;
import com.qualityos.calibration.repository.InstrumentRepository;
import com.qualityos.calibration.repository.InstrumentUsageRepository;
import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentService implements AttachmentPolicy {

    private static final String ENTITY = "Instrument";
    private static final String RESOURCE = "Instrument";
    private static final String ATTACHMENT_TYPE = "CALIBRATION_RECORD";
    private static final int DUE_SOON_DAYS = 14;
    private static final Duration OVERDUE_REMINDER_INTERVAL = Duration.ofDays(7);

    private final InstrumentRepository instruments;
    private final CalibrationRecordRepository records;
    private final InstrumentUsageRepository usages;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final TenantAccessService access;
    private final OrganizationService organizations;
    private final NotificationService notifications;
    private final AuditService audit;

    InstrumentService(
            InstrumentRepository instruments,
            CalibrationRecordRepository records,
            InstrumentUsageRepository usages,
            FacilityService facilities,
            FacilityScope scope,
            TenantAccessService access,
            OrganizationService organizations,
            NotificationService notifications,
            AuditService audit) {
        this.instruments = instruments;
        this.records = records;
        this.usages = usages;
        this.facilities = facilities;
        this.scope = scope;
        this.access = access;
        this.organizations = organizations;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<InstrumentResponse> list(
            String search, Instrument.Status status, UUID facilityId, boolean usableOnly, Pageable pageable) {
        LocalDate today = today();
        Specification<Instrument> filter = Specification
                .<Instrument>where(Searches.contains(search, "instrumentCode", "name", "category", "serialNumber"))
                .and(Searches.equalTo("status", usableOnly ? Instrument.Status.ACTIVE : status))
                .and(Searches.equalTo("facilityId", facilityId));
        if (usableOnly) {
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("nextDueOn"), today));
        }
        Optional<Set<UUID>> restriction = scope.restriction();
        if (restriction.isPresent()) {
            filter = filter.and((root, query, cb) -> root.get("facilityId").in(restriction.get()));
        }
        Map<UUID, String> members = access.activeMembers();
        return PageResponse.from(instruments.findAll(filter, pageable), i -> toResponse(i, members, today));
    }

    @Transactional(readOnly = true)
    public InstrumentResponse get(UUID id) {
        return toResponse(load(id));
    }

    @Transactional
    public InstrumentResponse create(SaveInstrumentRequest request) {
        if (instruments.existsByInstrumentCode(request.instrumentCode().trim())) {
            throw new ConflictException("Instrument ID %s is already in use.".formatted(request.instrumentCode()));
        }
        Instrument instrument = new Instrument(request.instrumentCode().trim(), request.nextDueOn());
        apply(instrument, request);
        instruments.saveAndFlush(instrument);
        audit.record("INSTRUMENT_REGISTERED", ENTITY, instrument.getId());
        return toResponse(instrument);
    }

    /** A new frequency applies from the last calibration, because that is what it measures from. */
    @Transactional
    public InstrumentResponse update(UUID id, SaveInstrumentRequest request) {
        Instrument instrument = load(id);
        instrument.requireNotRetired();
        apply(instrument, request);
        instrument.setInstrumentCode(request.instrumentCode().trim());
        instrument.setNextDueOn(instrument.getLastCalibratedOn() == null
                ? request.nextDueOn()
                : instrument.getLastCalibratedOn().plusDays(request.frequencyDays()));
        audit.record("INSTRUMENT_UPDATED", ENTITY, id);
        return toResponse(instrument);
    }

    @Transactional
    public InstrumentResponse changeStatus(UUID id, ChangeStatusRequest request) {
        Instrument instrument = load(id);
        String previous = instrument.getStatus().name();
        instrument.changeStatus(request.status(), request.reason().trim());
        audit.record("INSTRUMENT_STATUS_CHANGED", ENTITY, id, Map.of("status", previous),
                Map.of("status", request.status().name(), "reason", request.reason().trim()));
        return toResponse(instrument);
    }

    @Transactional(readOnly = true)
    public List<CalibrationResponse> history(UUID id) {
        load(id);
        return records.findByInstrumentIdOrderByCalibratedOnDescCreatedAtDesc(id).stream()
                .map(InstrumentService::toResponse)
                .toList();
    }

    @Transactional
    public CalibrationResponse recordCalibration(UUID id, RecordCalibrationRequest request) {
        Instrument instrument = load(id);
        instrument.requireNotRetired();
        if (instrument.getLastCalibratedOn() != null && request.calibratedOn().isBefore(instrument.getLastCalibratedOn())) {
            throw new BusinessRuleException("This is earlier than the last calibration.");
        }
        boolean failed = request.result() == CalibrationRecord.Result.FAIL;
        CalibrationRecord record = records.saveAndFlush(new CalibrationRecord(
                id,
                request.calibratedOn(),
                request.result(),
                request.performedBy().trim(),
                request.certificateNumber(),
                request.notes(),
                failed ? instrument.getNextDueOn() : request.calibratedOn().plusDays(instrument.getFrequencyDays())));
        instrument.calibrated(record);
        audit.record("CALIBRATION_RECORDED", ENTITY, id, null, Map.of("result", request.result().name()));
        if (failed) {
            long suspect = usage(id).stream().filter(UsageResponse::suspect).count();
            notifications.notify(recipients(instrument, Permissions.CALIBRATION_MANAGE, Permissions.NCR_ASSIGN),
                    NotificationType.CALIBRATION_FAILED,
                    instrument.getName() + " failed calibration",
                    "%d inspection(s) used it since its last good calibration.".formatted(suspect),
                    "/calibration/" + id);
        }
        return toResponse(record);
    }

    /** With a failure as the latest result, whatever was measured since the last good calibration is in doubt. */
    @Transactional(readOnly = true)
    public List<UsageResponse> usage(UUID id) {
        load(id);
        List<CalibrationRecord> history = records.findByInstrumentIdOrderByCalibratedOnDescCreatedAtDesc(id);
        boolean failed = !history.isEmpty() && history.getFirst().getResult() == CalibrationRecord.Result.FAIL;
        LocalDate lastGood = history.stream()
                .filter(record -> record.getResult() != CalibrationRecord.Result.FAIL)
                .map(CalibrationRecord::getCalibratedOn)
                .findFirst()
                .orElse(LocalDate.MIN);
        return usages.findByInstrumentIdOrderByUsedOnDesc(id).stream()
                .map(use -> new UsageResponse(use.getInspectionId(), use.getInspectionNumber(), use.getUsedOn(),
                        failed && !use.getUsedOn().isBefore(lastGood)))
                .toList();
    }

    /** Joins the inspection's transaction. An instrument already recorded on it is left alone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUse(UUID inspectionId, String inspectionNumber, Set<UUID> instrumentIds) {
        LocalDate today = today();
        Set<UUID> recorded = new HashSet<>();
        usages.findByInspectionId(inspectionId).forEach(use -> recorded.add(use.getInstrumentId()));
        for (UUID id : instrumentIds) {
            if (recorded.contains(id)) {
                continue;
            }
            Instrument instrument = load(id);
            if (instrument.getStatus() != Instrument.Status.ACTIVE) {
                throw new BusinessRuleException("%s is out of service.".formatted(instrument.getInstrumentCode()));
            }
            if (instrument.getNextDueOn().isBefore(today)) {
                throw new BusinessRuleException("%s is overdue for calibration, since %s.".formatted(
                        instrument.getInstrumentCode(), instrument.getNextDueOn()));
            }
            usages.save(new InstrumentUsage(id, inspectionId, inspectionNumber, today));
        }
    }

    @Transactional(readOnly = true)
    public List<UsedInstrument> usedBy(UUID inspectionId) {
        return usages.findByInspectionId(inspectionId).stream()
                .flatMap(use -> instruments.findById(use.getInstrumentId()).stream())
                .map(i -> new UsedInstrument(i.getId(), i.getInstrumentCode(), i.getName(), i.getNextDueOn()))
                .toList();
    }

    /** Runs under the current tenant, and tells each instrument's people once a week until it is calibrated. */
    @Transactional
    public int remindOverdue() {
        Instant now = Instant.now();
        List<Instrument> overdue = instruments.findOverdue(today(), now.minus(OVERDUE_REMINDER_INTERVAL));
        overdue.forEach(instrument -> {
            remind(instrument, NotificationType.CALIBRATION_OVERDUE, EmailTemplate.CALIBRATION_OVERDUE,
                    instrument.getName() + " is overdue for calibration");
            instrument.setOverdueNotifiedAt(now);
        });
        return overdue.size();
    }

    /** Reminds once, shortly before the date. Runs under the current tenant. */
    @Transactional
    public int remindDueSoon() {
        Instant now = Instant.now();
        LocalDate today = today();
        List<Instrument> due = instruments.findDueSoon(today, today.plusDays(DUE_SOON_DAYS));
        due.forEach(instrument -> {
            remind(instrument, NotificationType.CALIBRATION_DUE_SOON, EmailTemplate.CALIBRATION_DUE,
                    instrument.getName() + " is due for calibration");
            instrument.setDueRemindedAt(now);
        });
        return due.size();
    }

    private void remind(Instrument instrument, NotificationType type, EmailTemplate template, String title) {
        String due = instrument.getNextDueOn().toString();
        notifications.notify(recipients(instrument, Permissions.CALIBRATION_MANAGE), type, title, "Due on " + due + ".",
                "/calibration/" + instrument.getId(),
                new EmailContent(template, Map.of("instrumentName", instrument.getName(), "dueDate", due)));
    }

    private Set<UUID> recipients(Instrument instrument, String... permissions) {
        Set<UUID> people = new HashSet<>();
        people.add(instrument.getCustodianId());
        for (String permission : permissions) {
            people.addAll(access.holdersOf(permission));
        }
        people.remove(null);
        return scope.allowedIn(instrument.getFacilityId(), people);
    }

    private Instrument load(UUID id) {
        Instrument instrument = instruments.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id.toString()));
        scope.require(Set.of(instrument.getFacilityId()), RESOURCE, id.toString());
        return instrument;
    }

    private void apply(Instrument instrument, SaveInstrumentRequest request) {
        facilities.requireActive(request.facilityId());
        scope.require(Set.of(request.facilityId()), "Facility", request.facilityId().toString());
        if (request.custodianId() != null && access.activeMemberContact(request.custodianId()).isEmpty()) {
            throw new BusinessRuleException("The custodian must be an active member of this organisation.");
        }
        instrument.setName(request.name().trim());
        instrument.setCategory(request.category().trim());
        instrument.setManufacturer(request.manufacturer());
        instrument.setModel(request.model());
        instrument.setSerialNumber(request.serialNumber());
        instrument.setFacilityId(request.facilityId());
        instrument.setLocation(request.location());
        instrument.setCustodianId(request.custodianId());
        instrument.setFrequencyDays(request.frequencyDays());
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    private InstrumentResponse toResponse(Instrument instrument) {
        return toResponse(instrument, access.activeMembers(), today());
    }

    private static InstrumentResponse toResponse(Instrument i, Map<UUID, String> members, LocalDate today) {
        String dueState = i.getNextDueOn().isBefore(today) ? "OVERDUE"
                : i.getNextDueOn().isAfter(today.plusDays(DUE_SOON_DAYS)) ? "OK" : "DUE_SOON";
        return new InstrumentResponse(i.getId(), i.getInstrumentCode(), i.getName(), i.getCategory(),
                i.getManufacturer(), i.getModel(), i.getSerialNumber(), i.getFacilityId(), i.getLocation(),
                i.getCustodianId(), members.get(i.getCustodianId()), i.getFrequencyDays(), i.getLastCalibratedOn(),
                i.getNextDueOn(), i.getStatus().name(), i.getStatusReason(), dueState);
    }

    private static CalibrationResponse toResponse(CalibrationRecord record) {
        return new CalibrationResponse(record.getId(), record.getCalibratedOn(), record.getResult().name(),
                record.getPerformedBy(), record.getCertificateNumber(), record.getNotes(), record.getNextDueOn(),
                record.getCreatedAt());
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.CALIBRATION_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.CALIBRATION_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return records.findById(entityId)
                .flatMap(record -> instruments.findById(record.getInstrumentId()))
                .filter(instrument -> scope.allows(instrument.getFacilityId()))
                .isPresent();
    }
}
