package com.qualityos.production.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.production.dto.DowntimeDtos.DowntimeResponse;
import com.qualityos.production.dto.DowntimeDtos.DowntimeSummary;
import com.qualityos.production.dto.DowntimeDtos.EndDowntimeRequest;
import com.qualityos.production.dto.DowntimeDtos.GroupBy;
import com.qualityos.production.dto.DowntimeDtos.RecordDowntimeRequest;
import com.qualityos.production.dto.DowntimeDtos.SummaryRow;
import com.qualityos.production.dto.DowntimeDtos.UpdateDowntimeRequest;
import com.qualityos.production.entity.DowntimeRecord;
import com.qualityos.production.entity.DowntimeRecord.Source;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.OperationEvent.LossCategory;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.DowntimeRecordRepository;
import com.qualityos.production.repository.MachineRepository;
import com.qualityos.production.repository.WorkCenterRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production downtime: one record per stop. The shop floor opens one when an operation is paused or blocked for a
 * reason and ends it when the operation goes on; a machine set to breakdown or maintenance by hand opens one until it
 * is set back. Anything else, including maintenance planned ahead, is entered by hand. See docs/MANUFACTURING.md.
 */
@Service
public class DowntimeService implements AttachmentPolicy {

    static final String ENTITY = "DowntimeRecord";
    private static final int MAX_SUMMARY_DAYS = 366;
    private static final Set<WorkOrderOperation.Status> STOPPED = Set.of(WorkOrderOperation.Status.PAUSED,
            WorkOrderOperation.Status.BLOCKED);
    private static final Set<Machine.Status> MACHINE_DOWN = Set.of(Machine.Status.BREAKDOWN, Machine.Status.MAINTENANCE);

    private final DowntimeRecordRepository records;
    private final WorkCenterRepository centres;
    private final MachineRepository machines;
    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final FacilityService facilities;
    private final ProductionCalendar calendar;
    private final FacilityScope scope;
    private final TenantAccessService access;
    private final AuditService audit;

    @SuppressWarnings("java:S107")
    DowntimeService(
            DowntimeRecordRepository records,
            WorkCenterRepository centres,
            MachineRepository machines,
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            FacilityService facilities,
            ProductionCalendar calendar,
            FacilityScope scope,
            TenantAccessService access,
            AuditService audit) {
        this.records = records;
        this.centres = centres;
        this.machines = machines;
        this.workOrders = workOrders;
        this.operations = operations;
        this.facilities = facilities;
        this.calendar = calendar;
        this.scope = scope;
        this.access = access;
        this.audit = audit;
    }

    // From the floor and the machines

    /**
     * Keeps the operation's stop in step with its status: a pause or block with a reason category opens one (ending
     * any before it), and anything else ends the open one. Joins the shop floor's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void operationChanged(WorkOrder order, WorkOrderOperation operation, LossCategory category, String reason) {
        Instant now = Instant.now();
        Optional<DowntimeRecord> open = records.findByOperationIdAndEndedAtIsNull(operation.getId());
        open.ifPresent(found -> {
            found.end(now, TenantContext.getCurrentUserId());
            records.saveAndFlush(found);
        });
        if (STOPPED.contains(operation.getStatus()) && category != null) {
            WorkCenter centre = centres.findById(operation.getWorkCenterId()).orElseThrow();
            records.saveAndFlush(new DowntimeRecord(Source.SHOP_FLOOR, order.getFacilityId(), centre.getId(),
                    operation.getMachineId(), order.getId(), operation.getId(), category, now,
                    reason == null || reason.isBlank() ? "Operation %d %s".formatted(operation.getSequence(),
                            label(category)) : reason.trim()));
        }
    }

    /** A machine set down by hand opens a stop until it is set back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void machineChanged(Machine machine, Machine.Status from, String reason) {
        Instant now = Instant.now();
        if (MACHINE_DOWN.contains(from) && machine.getStatus() != from) {
            records.findByMachineIdAndSourceAndEndedAtIsNull(machine.getId(), Source.MACHINE).ifPresent(found -> {
                found.end(now, TenantContext.getCurrentUserId());
                records.saveAndFlush(found);
            });
        }
        if (MACHINE_DOWN.contains(machine.getStatus()) && machine.getStatus() != from) {
            WorkCenter centre = centres.findById(machine.getWorkCenterId()).orElseThrow();
            LossCategory category = machine.getStatus() == Machine.Status.BREAKDOWN ? LossCategory.MACHINE_BREAKDOWN
                    : LossCategory.PLANNED_MAINTENANCE;
            records.saveAndFlush(new DowntimeRecord(Source.MACHINE, centre.getFacilityId(), centre.getId(),
                    machine.getId(), null, null, category, now, reason == null || reason.isBlank()
                            ? "Machine %s %s".formatted(machine.getCode(), label(category)) : reason.trim()));
        }
    }

    // Reading

    @Transactional(readOnly = true)
    @SuppressWarnings("java:S107")
    public PageResponse<DowntimeResponse> list(UUID facilityId, UUID workCenterId, UUID machineId, UUID workOrderId,
            LossCategory category, Boolean open, LocalDate from, LocalDate to, Pageable pageable) {
        ZoneId zone = calendar.zone();
        Specification<DowntimeRecord> filter = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> where = new ArrayList<>();
            if (facilityId != null) {
                where.add(cb.equal(root.get("facilityId"), facilityId));
            }
            if (workCenterId != null) {
                where.add(cb.equal(root.get("workCenterId"), workCenterId));
            }
            if (machineId != null) {
                where.add(cb.equal(root.get("machineId"), machineId));
            }
            if (workOrderId != null) {
                where.add(cb.equal(root.get("workOrderId"), workOrderId));
            }
            if (category != null) {
                where.add(cb.equal(root.get("category"), category));
            }
            if (Boolean.TRUE.equals(open)) {
                where.add(cb.isNull(root.get("endedAt")));
            } else if (Boolean.FALSE.equals(open)) {
                where.add(cb.isNotNull(root.get("endedAt")));
            }
            if (to != null) {
                where.add(cb.lessThan(root.get("startedAt"), calendar.startOf(to.plusDays(1), zone)));
            }
            if (from != null) {
                Instant start = calendar.startOf(from, zone);
                where.add(cb.or(cb.isNull(root.get("endedAt")), cb.greaterThan(root.get("endedAt"), start)));
            }
            return cb.and(where.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<DowntimeRecord> page = records.findAll(scope.limit(filter, "facilityId"), PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "startedAt")));
        Function<DowntimeRecord, DowntimeResponse> mapper = responses(page.getContent());
        return PageResponse.from(page, mapper);
    }

    @Transactional(readOnly = true)
    public DowntimeResponse get(UUID id) {
        DowntimeRecord record = load(id);
        return responses(List.of(record)).apply(record);
    }

    /** Stops of one work order, for its page. */
    @Transactional(readOnly = true)
    public List<DowntimeResponse> ofOrder(UUID workOrderId) {
        List<DowntimeRecord> found = records.findByWorkOrderIdOrderByStartedAtAsc(workOrderId);
        return found.stream().map(responses(found)).toList();
    }

    /** Minutes of downtime in the period, grouped as asked. Open stops count up to now. */
    @Transactional(readOnly = true)
    public DowntimeSummary summary(LocalDate from, LocalDate to, UUID facilityId, GroupBy groupBy) {
        ProductionCalendar.requirePeriod(from, to, MAX_SUMMARY_DAYS);
        ZoneId zone = calendar.zone();
        Instant start = calendar.startOf(from, zone);
        Instant end = calendar.startOf(to.plusDays(1), zone);
        Instant now = Instant.now();
        List<DowntimeRecord> found = overlapping(facilityId, start, end);
        Map<UUID, WorkCenter> centreById = centres.findAllById(found.stream().map(DowntimeRecord::getWorkCenterId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(WorkCenter::getId, c -> c));
        Map<String, double[]> totals = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (DowntimeRecord record : found) {
            double minutes = record.minutesWithin(start, end, now);
            String key = switch (groupBy) {
                case MACHINE -> record.getMachineId() == null ? "none" : record.getMachineId().toString();
                case WORK_CENTER -> record.getWorkCenterId().toString();
                case PRODUCTION_LINE -> Optional.ofNullable(centreById.get(record.getWorkCenterId()))
                        .map(WorkCenter::getProductionLineId).map(UUID::toString).orElse("none");
                case FACILITY -> record.getFacilityId().toString();
                case CATEGORY -> record.getCategory().name();
            };
            double[] sums = totals.computeIfAbsent(key, ignored -> new double[2]);
            sums[record.isPlanned() ? 0 : 1] += minutes;
            counts.merge(key, 1L, Long::sum);
        }
        Map<String, String> labels = labels(groupBy, totals.keySet(), centreById);
        List<SummaryRow> rows = totals.entrySet().stream()
                .map(entry -> new SummaryRow(entry.getKey(), labels.getOrDefault(entry.getKey(), entry.getKey()),
                        counts.get(entry.getKey()), minutes(entry.getValue()[0] + entry.getValue()[1]),
                        minutes(entry.getValue()[0]), minutes(entry.getValue()[1])))
                .sorted(Comparator.comparing(SummaryRow::minutes).reversed().thenComparing(SummaryRow::label))
                .toList();
        double planned = totals.values().stream().mapToDouble(sums -> sums[0]).sum();
        double unplanned = totals.values().stream().mapToDouble(sums -> sums[1]).sum();
        return new DowntimeSummary(from, to, zone.getId(), groupBy, rows, minutes(planned + unplanned),
                minutes(planned), minutes(unplanned));
    }

    /** Stops overlapping the period in one facility, or every facility the caller may see. */
    @Transactional(readOnly = true)
    public List<DowntimeRecord> overlapping(UUID facilityId, Instant start, Instant end) {
        Collection<UUID> facilityIds;
        if (facilityId != null) {
            scope.requireAllowed(facilityId, "Facility", facilityId.toString());
            facilityIds = List.of(facilityId);
        } else {
            facilityIds = scope.restriction().map(allowed -> (Collection<UUID>) allowed)
                    .orElseGet(facilities::allIds);
        }
        return facilityIds.isEmpty() ? List.of() : records.overlapping(facilityIds, start, end);
    }

    // Recording by hand

    @Transactional
    public DowntimeResponse record(RecordDowntimeRequest request) {
        if (request.category() == LossCategory.ORDER_ON_HOLD) {
            throw new BusinessRuleException("A work order on hold is recorded by putting the order on hold.");
        }
        WorkCenter centre;
        Machine machine = null;
        if (request.machineId() != null) {
            machine = machines.findById(request.machineId())
                    .orElseThrow(() -> new ResourceNotFoundException("Machine", request.machineId()));
            centre = centres.findById(machine.getWorkCenterId()).orElseThrow();
            if (request.workCenterId() != null && !request.workCenterId().equals(centre.getId())) {
                throw new BusinessRuleException("Machine %s is at work centre %s.".formatted(machine.getCode(),
                        centre.getCode()));
            }
        } else if (request.workCenterId() != null) {
            centre = centres.findById(request.workCenterId())
                    .orElseThrow(() -> new ResourceNotFoundException("WorkCenter", request.workCenterId()));
        } else {
            throw new BusinessRuleException("Say which machine or work centre stopped.");
        }
        scope.requireAllowed(centre.getFacilityId(), "WorkCenter", centre.getId().toString());
        if (request.workOrderId() != null) {
            WorkOrder order = workOrders.findById(request.workOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", request.workOrderId()));
            if (!order.getFacilityId().equals(centre.getFacilityId())) {
                throw new BusinessRuleException("Work order %s is at another facility."
                        .formatted(order.getWorkOrderNumber()));
            }
        }
        DowntimeRecord record = new DowntimeRecord(Source.MANUAL, centre.getFacilityId(), centre.getId(),
                machine == null ? null : machine.getId(), request.workOrderId(), null, request.category(),
                request.startedAt(), request.reason().trim());
        record.setResponsibleDepartment(blankToNull(request.responsibleDepartment()));
        record.setComments(blankToNull(request.comments()));
        if (request.endedAt() != null) {
            record.end(request.endedAt(), TenantContext.getCurrentUserId());
        }
        requireSensible(record);
        records.saveAndFlush(record);
        audit.record("DOWNTIME_RECORDED", ENTITY, record.getId(), null, snapshot(record));
        return get(record.getId());
    }

    @Transactional
    public DowntimeResponse update(UUID id, UpdateDowntimeRequest request) {
        DowntimeRecord record = load(id);
        Map<String, Object> before = snapshot(record);
        boolean retimable = record.getSource() == Source.MANUAL;
        if (!retimable && (request.category() != null && request.category() != record.getCategory()
                || request.startedAt() != null && !request.startedAt().equals(record.getStartedAt())
                || request.endedAt() != null && !request.endedAt().equals(record.getEndedAt()))) {
            throw new BusinessRuleException("This stop was recorded as it happened; only who was responsible, the "
                    + "reason and comments can be changed.");
        }
        if (request.category() == LossCategory.ORDER_ON_HOLD && record.getCategory() != LossCategory.ORDER_ON_HOLD) {
            throw new BusinessRuleException("A work order on hold is recorded by putting the order on hold.");
        }
        if (request.category() != null) {
            record.categorise(request.category());
        }
        if (request.startedAt() != null) {
            record.setStartedAt(request.startedAt());
        }
        if (request.endedAt() != null) {
            record.end(request.endedAt(), TenantContext.getCurrentUserId());
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            record.setReason(request.reason().trim());
        }
        record.setResponsibleDepartment(blankToNull(request.responsibleDepartment()));
        record.setComments(blankToNull(request.comments()));
        requireSensible(record);
        records.saveAndFlush(record);
        audit.record("DOWNTIME_UPDATED", ENTITY, id, before, snapshot(record));
        return get(id);
    }

    /** Ends a stop entered by hand. Stops from the floor or a machine end when the operation or machine goes on. */
    @Transactional
    public DowntimeResponse end(UUID id, EndDowntimeRequest request) {
        DowntimeRecord record = load(id);
        if (!record.isOpen()) {
            throw new BusinessRuleException("This stop has already ended.");
        }
        if (record.getSource() != Source.MANUAL) {
            throw new BusinessRuleException(record.getSource() == Source.SHOP_FLOOR
                    ? "This stop ends when the operation is resumed or unblocked."
                    : "This stop ends when the machine is set back to work.");
        }
        record.end(request == null || request.endedAt() == null ? Instant.now() : request.endedAt(),
                TenantContext.getCurrentUserId());
        requireSensible(record);
        records.saveAndFlush(record);
        audit.record("DOWNTIME_ENDED", ENTITY, id, Map.of("endedAt", ""), snapshot(record));
        return get(id);
    }

    // Rules

    /** Only planned maintenance can be entered ahead; a stop cannot end before it starts or end in the future. */
    private static void requireSensible(DowntimeRecord record) {
        Instant now = Instant.now().plusSeconds(60);
        if (record.getEndedAt() != null && record.getEndedAt().isBefore(record.getStartedAt())) {
            throw new BusinessRuleException("A stop cannot end before it starts.");
        }
        if (!record.isPlanned() && (record.getStartedAt().isAfter(now)
                || record.getEndedAt() != null && record.getEndedAt().isAfter(now))) {
            throw new BusinessRuleException("Only planned maintenance can be entered ahead of time.");
        }
        if (record.isPlanned() && record.getStartedAt().isAfter(now) && record.getEndedAt() == null) {
            throw new BusinessRuleException("Say when the planned maintenance ends.");
        }
    }

    private DowntimeRecord load(UUID id) {
        DowntimeRecord record = records.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(record.getFacilityId(), ENTITY, id.toString());
        return record;
    }

    private Map<String, String> labels(GroupBy groupBy, Set<String> keys, Map<UUID, WorkCenter> centreById) {
        Set<UUID> ids = keys.stream().filter(key -> !"none".equals(key) && groupBy != GroupBy.CATEGORY)
                .map(UUID::fromString).collect(Collectors.toSet());
        Map<String, String> labels = new HashMap<>();
        labels.put("none", groupBy == GroupBy.MACHINE ? "No machine" : "No production line");
        switch (groupBy) {
            case MACHINE -> machines.findAllById(ids).forEach(m -> labels.put(m.getId().toString(), m.getCode()));
            case WORK_CENTER -> centreById.values().forEach(c -> labels.put(c.getId().toString(), c.getCode()));
            case PRODUCTION_LINE, FACILITY -> facilities.codes(ids)
                    .forEach((id, code) -> labels.put(id.toString(), code));
            case CATEGORY -> keys.forEach(key -> labels.put(key, label(LossCategory.valueOf(key))));
        }
        return labels;
    }

    private Function<DowntimeRecord, DowntimeResponse> responses(Collection<DowntimeRecord> found) {
        Set<UUID> facilityIds = found.stream().map(DowntimeRecord::getFacilityId).collect(Collectors.toSet());
        Map<UUID, String> codes = facilities.codes(facilityIds);
        Map<UUID, String> centreCodes = centres.findAllById(found.stream().map(DowntimeRecord::getWorkCenterId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(WorkCenter::getId,
                        WorkCenter::getCode));
        Map<UUID, String> machineCodes = machines.findAllById(found.stream().map(DowntimeRecord::getMachineId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Machine::getId, Machine::getCode));
        Map<UUID, String> orderNumbers = workOrders.findAllById(found.stream().map(DowntimeRecord::getWorkOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(WorkOrder::getId, WorkOrder::getWorkOrderNumber));
        Map<UUID, Integer> sequences = operations.findAllById(found.stream().map(DowntimeRecord::getOperationId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(WorkOrderOperation::getId, WorkOrderOperation::getSequence));
        Map<UUID, String> members = access.activeMembers();
        Instant now = Instant.now();
        return record -> new DowntimeResponse(record.getId(), record.getFacilityId(),
                codes.get(record.getFacilityId()), record.getWorkCenterId(), centreCodes.get(record.getWorkCenterId()),
                record.getMachineId(), record.getMachineId() == null ? null : machineCodes.get(record.getMachineId()),
                record.getWorkOrderId(), record.getWorkOrderId() == null ? null
                        : orderNumbers.get(record.getWorkOrderId()),
                record.getOperationId(), record.getOperationId() == null ? null
                        : sequences.get(record.getOperationId()),
                record.getSource(), record.getCategory(), record.isPlanned(), record.getStartedAt(),
                record.getEndedAt(), minutes(record.minutesWithin(record.getStartedAt(),
                        record.getEndedAt() == null ? now : record.getEndedAt(), now)),
                record.getReason(), record.getResponsibleDepartment(), record.getComments(),
                name(members, record.getCreatedBy()), name(members, record.getEndedBy()));
    }

    private static String name(Map<UUID, String> members, UUID id) {
        return id == null ? null : members.getOrDefault(id, "Former member");
    }

    private static Map<String, Object> snapshot(DowntimeRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("category", record.getCategory().name());
        values.put("startedAt", record.getStartedAt().toString());
        values.put("endedAt", record.getEndedAt() == null ? "" : record.getEndedAt().toString());
        values.put("reason", record.getReason());
        values.put("responsibleDepartment", Objects.toString(record.getResponsibleDepartment(), ""));
        return values;
    }

    static BigDecimal minutes(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    static String label(LossCategory category) {
        return category.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // Attachments: evidence of a stop

    @Override
    public String entityType() {
        return "DOWNTIME";
    }

    @Override
    public String viewPermission() {
        return Permissions.MANUFACTURING_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.DOWNTIME_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return records.findById(entityId).filter(record -> scope.allows(record.getFacilityId())).isPresent();
    }

}
