package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.dto.FacilityDtos.ShiftCalendar;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.SchedulingDtos.CentreLoad;
import com.qualityos.production.dto.SchedulingDtos.Conflict;
import com.qualityos.production.dto.SchedulingDtos.DayLoad;
import com.qualityos.production.dto.SchedulingDtos.RescheduleRequest;
import com.qualityos.production.dto.SchedulingDtos.RescheduleResponse;
import com.qualityos.production.dto.SchedulingDtos.ScheduledOrder;
import com.qualityos.production.dto.SchedulingDtos.SchedulingBoard;
import com.qualityos.production.entity.DowntimeRecord;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.RevisionedDefinition;
import com.qualityos.production.entity.Routing;
import com.qualityos.production.entity.RoutingOperation;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.MachineRepository;
import com.qualityos.production.repository.RoutingOperationRepository;
import com.qualityos.production.repository.RoutingRepository;
import com.qualityos.production.repository.WorkCenterRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Basic production scheduling: capacity validation, not optimisation. Capacity is each work centre's shift time times
 * its units (active machines, or its capacity units without machines), less planned downtime. Load is each open
 * order's remaining planned time, spread evenly over the working days left in its planned dates. Where load passes
 * capacity, the board says so. See docs/MANUFACTURING.md.
 */
@Service
public class SchedulingService {

    private static final int MAX_DAYS = 62;
    private static final double SLACK_MINUTES = 0.5;
    private static final Set<WorkOrder.Status> SCHEDULED = EnumSet.of(WorkOrder.Status.APPROVED,
            WorkOrder.Status.RELEASED, WorkOrder.Status.IN_PROGRESS, WorkOrder.Status.ON_HOLD);
    private static final Set<WorkOrderOperation.Status> DONE = EnumSet.of(WorkOrderOperation.Status.COMPLETED,
            WorkOrderOperation.Status.CANCELLED);

    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final WorkCenterRepository centres;
    private final MachineRepository machines;
    private final RoutingRepository routings;
    private final RoutingOperationRepository routingOperations;
    private final DowntimeService downtime;
    private final ProductionCalendar calendar;
    private final ProductService products;
    private final FacilityScope scope;
    private final AuditService audit;

    @SuppressWarnings("java:S107")
    SchedulingService(
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            WorkCenterRepository centres,
            MachineRepository machines,
            RoutingRepository routings,
            RoutingOperationRepository routingOperations,
            DowntimeService downtime,
            ProductionCalendar calendar,
            ProductService products,
            FacilityScope scope,
            AuditService audit) {
        this.workOrders = workOrders;
        this.operations = operations;
        this.centres = centres;
        this.machines = machines;
        this.routings = routings;
        this.routingOperations = routingOperations;
        this.downtime = downtime;
        this.calendar = calendar;
        this.products = products;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public SchedulingBoard board(UUID facilityId, LocalDate from, LocalDate to) {
        ProductionCalendar.requirePeriod(from, to, MAX_DAYS);
        scope.requireAllowed(facilityId, "Facility", facilityId.toString());
        ZoneId zone = calendar.zone();
        LocalDate today = LocalDate.now(zone);
        Instant now = Instant.now();
        List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();

        List<WorkCenter> centreList = centres.findByFacilityIdInAndArchivedAtIsNullOrderByCode(List.of(facilityId));
        Map<UUID, WorkCenter> centreById = centreList.stream().collect(Collectors.toMap(WorkCenter::getId, c -> c,
                (a, b) -> a, LinkedHashMap::new));
        Map<UUID, Long> machineCount = machines.findByWorkCenterIdIn(centreById.keySet()).stream()
                .filter(machine -> machine.getStatus() != Machine.Status.INACTIVE)
                .collect(Collectors.groupingBy(Machine::getWorkCenterId, Collectors.counting()));
        Map<UUID, Integer> units = new HashMap<>();
        centreById.values().forEach(centre -> units.put(centre.getId(),
                machineCount.getOrDefault(centre.getId(), 0L) > 0 ? machineCount.get(centre.getId()).intValue()
                        : centre.getCapacityUnits()));
        List<ShiftCalendar> shifts = calendar.shifts(List.of(facilityId)).getOrDefault(facilityId, List.of());

        // Planned downtime known ahead takes time out of capacity.
        Map<UUID, Map<LocalDate, Double>> plannedDown = new HashMap<>();
        for (DowntimeRecord record : downtime.overlapping(facilityId, calendar.startOf(from, zone),
                calendar.startOf(to.plusDays(1), zone))) {
            if (!record.isPlanned() || !centreById.containsKey(record.getWorkCenterId())) {
                continue;
            }
            for (LocalDate day : days) {
                double minutes = record.minutesWithin(calendar.startOf(day, zone),
                        calendar.startOf(day.plusDays(1), zone), now);
                if (minutes > 0) {
                    plannedDown.computeIfAbsent(record.getWorkCenterId(), ignored -> new HashMap<>())
                            .merge(day, minutes, Double::sum);
                }
            }
        }

        // Load from each open order.
        List<WorkOrder> open = workOrders.findByFacilityIdAndStatusInAndPlannedStartLessThanEqual(facilityId,
                SCHEDULED, to).stream()
                .filter(order -> !order.getPlannedEnd().isBefore(from) || order.getPlannedEnd().isBefore(today))
                .toList();
        Map<UUID, List<WorkOrderOperation>> released = operations.findByWorkOrderIdInOrderBySequence(
                open.stream().filter(order -> order.getReleasedAt() != null).map(WorkOrder::getId).toList())
                .stream().collect(Collectors.groupingBy(WorkOrderOperation::getWorkOrderId));
        Map<UUID, ProductReference> productRefs = products.references(
                open.stream().map(WorkOrder::getProductId).collect(Collectors.toSet()));
        Map<UUID, Map<LocalDate, Double>> load = new HashMap<>();
        Map<UUID, Map<LocalDate, Set<String>>> contributors = new HashMap<>();
        Map<UUID, List<String>> warnings = new HashMap<>();
        Map<UUID, Double> remainingByOrder = new HashMap<>();
        for (WorkOrder order : open) {
            List<String> notes = new ArrayList<>();
            Map<UUID, Double> remaining = remaining(order, released.getOrDefault(order.getId(), List.of()), now,
                    notes);
            remaining.keySet().stream().filter(centre -> !centreById.containsKey(centre)).findAny()
                    .ifPresent(ignored -> notes.add("Some of its operations are at work centres outside this "
                            + "facility or archived, so they are not on this board."));
            remaining.keySet().retainAll(centreById.keySet());
            remainingByOrder.put(order.getId(), remaining.values().stream().mapToDouble(Double::doubleValue).sum());

            LocalDate windowStart = order.getPlannedStart().isAfter(today) ? order.getPlannedStart() : today;
            LocalDate windowEnd = order.getPlannedEnd();
            if (windowEnd.isBefore(today)) {
                notes.add("It was planned to finish on %s, which has passed.".formatted(windowEnd));
                windowStart = today;
                windowEnd = today;
            }
            List<LocalDate> window = windowStart.datesUntil(windowEnd.plusDays(1)).toList();
            List<LocalDate> working = window.stream()
                    .filter(day -> ProductionCalendar.workingMinutes(shifts, day) > 0).toList();
            if (working.isEmpty()) {
                if (shifts.isEmpty()) {
                    notes.add("The facility has no shifts, so there is no capacity to plan against.");
                } else {
                    notes.add("No shift works between %s and %s.".formatted(windowStart, windowEnd));
                }
                working = window;
            }
            for (Map.Entry<UUID, Double> entry : remaining.entrySet()) {
                if (entry.getValue() <= 0) {
                    continue;
                }
                WorkCenter centre = centreById.get(entry.getKey());
                double available = working.stream().mapToDouble(day -> capacity(shifts, day,
                        units.get(centre.getId()), plannedDown.getOrDefault(centre.getId(), Map.of()))).sum();
                if (entry.getValue() > available + SLACK_MINUTES) {
                    notes.add("It needs %s h at %s, which has %s h in its planned dates.".formatted(
                            hours(entry.getValue()), centre.getCode(), hours(available)));
                }
                double share = entry.getValue() / working.size();
                for (LocalDate day : working) {
                    if (!day.isBefore(from) && !day.isAfter(to)) {
                        load.computeIfAbsent(centre.getId(), ignored -> new HashMap<>()).merge(day, share, Double::sum);
                        contributors.computeIfAbsent(centre.getId(), ignored -> new HashMap<>())
                                .computeIfAbsent(day, ignored -> new TreeSet<>()).add(order.getWorkOrderNumber());
                    }
                }
            }
            warnings.put(order.getId(), notes);
        }

        // Each centre's days, and where load passes capacity.
        List<CentreLoad> centreLoads = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Map<String, Set<String>> overloadNotes = new HashMap<>();
        for (WorkCenter centre : centreById.values()) {
            List<DayLoad> dayLoads = new ArrayList<>();
            for (LocalDate day : days) {
                double down = plannedDown.getOrDefault(centre.getId(), Map.of()).getOrDefault(day, 0.0);
                double capacity = capacity(shifts, day, units.get(centre.getId()),
                        plannedDown.getOrDefault(centre.getId(), Map.of()));
                double booked = load.getOrDefault(centre.getId(), Map.of()).getOrDefault(day, 0.0);
                boolean over = booked > capacity + SLACK_MINUTES;
                dayLoads.add(new DayLoad(day, minutes(capacity), minutes(down), minutes(booked), over));
                if (over) {
                    Set<String> numbers = contributors.get(centre.getId()).get(day);
                    conflicts.add(new Conflict(centre.getId(), centre.getCode(), day, minutes(capacity),
                            minutes(booked), List.copyOf(numbers)));
                    numbers.forEach(number -> overloadNotes.computeIfAbsent(number, ignored -> new LinkedHashSet<>())
                            .add("%s on %s".formatted(centre.getCode(), day)));
                }
            }
            centreLoads.add(new CentreLoad(centre.getId(), centre.getCode(), centre.getName(),
                    units.get(centre.getId()), dayLoads));
        }

        List<ScheduledOrder> scheduled = open.stream()
                .sorted(Comparator.comparing((WorkOrder order) -> order.getPriority().ordinal()).reversed()
                        .thenComparing(WorkOrder::getPlannedStart).thenComparing(WorkOrder::getWorkOrderNumber))
                .map(order -> {
                    List<String> notes = new ArrayList<>(warnings.get(order.getId()));
                    Set<String> over = overloadNotes.get(order.getWorkOrderNumber());
                    if (over != null) {
                        notes.add("Over capacity: " + String.join(", ", over) + ".");
                    }
                    ProductReference product = productRefs.get(order.getProductId());
                    return new ScheduledOrder(order.getId(), order.getWorkOrderNumber(),
                            product == null ? null : product.code(), order.getStatus(), order.getPriority(),
                            order.getPlannedStart(), order.getPlannedEnd(),
                            minutes(remainingByOrder.get(order.getId())), notes);
                })
                .toList();
        return new SchedulingBoard(facilityId, from, to, zone.getId(), !shifts.isEmpty(), centreLoads, scheduled,
                conflicts);
    }

    /** Moves an open order's planned dates, and its priority if given, and says where it now runs over capacity. */
    @Transactional
    public RescheduleResponse reschedule(UUID id, RescheduleRequest request) {
        WorkOrder order = workOrders.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(WorkOrderService.ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), WorkOrderService.ENTITY, order.getWorkOrderNumber());
        if (!order.getStatus().isOpen()) {
            throw new BusinessRuleException("Work order %s is closed, so it cannot be rescheduled."
                    .formatted(order.getWorkOrderNumber()));
        }
        if (request.plannedEnd().isBefore(request.plannedStart())) {
            throw new BusinessRuleException("The planned end is before the planned start.");
        }
        Map<String, Object> before = Map.of("plannedStart", order.getPlannedStart().toString(),
                "plannedEnd", order.getPlannedEnd().toString(), "priority", order.getPriority().name());
        order.setPlannedStart(request.plannedStart());
        order.setPlannedEnd(request.plannedEnd());
        if (request.priority() != null) {
            order.setPriority(request.priority());
        }
        workOrders.saveAndFlush(order);
        audit.record("WORK_ORDER_RESCHEDULED", WorkOrderService.ENTITY, id, before, Map.of(
                "plannedStart", order.getPlannedStart().toString(), "plannedEnd", order.getPlannedEnd().toString(),
                "priority", order.getPriority().name(), "reason", request.reason().trim()));
        LocalDate today = LocalDate.now(calendar.zone());
        LocalDate from = order.getPlannedStart().isBefore(today) ? today : order.getPlannedStart();
        LocalDate to = order.getPlannedEnd().isBefore(from) ? from : order.getPlannedEnd();
        if (to.isAfter(from.plusDays(MAX_DAYS - 1L))) {
            to = from.plusDays(MAX_DAYS - 1L);
        }
        Optional<ScheduledOrder> placed = board(order.getFacilityId(), from, to).orders().stream()
                .filter(found -> found.id().equals(id)).findFirst();
        return new RescheduleResponse(placed.orElse(null));
    }

    // Rules

    /**
     * Minutes still to work at each work centre. A released order counts its operations' planned setup and run time
     * less what has run; an approved one, the routing in effect on its planned start.
     */
    private Map<UUID, Double> remaining(WorkOrder order, List<WorkOrderOperation> steps, Instant now,
            List<String> notes) {
        Map<UUID, Double> remaining = new LinkedHashMap<>();
        if (order.getReleasedAt() != null) {
            for (WorkOrderOperation step : steps) {
                if (DONE.contains(step.getStatus())) {
                    continue;
                }
                double planned = step.getPlannedSetupMinutes().add(step.getPlannedRunMinutes()).doubleValue();
                double worked = step.runningSecondsAt(now) / 60.0;
                remaining.merge(step.getWorkCenterId(), Math.max(planned - worked, 0), Double::sum);
            }
            if (steps.isEmpty()) {
                notes.add("It has no operations, so it puts no load on any work centre.");
            }
            return remaining;
        }
        Optional<Routing> routing = routings.findApprovedOn(order.getProductId(), order.getPlannedStart(),
                RevisionedDefinition.Status.APPROVED).stream().findFirst();
        if (routing.isEmpty()) {
            notes.add("No approved routing is in effect on its planned start, so its load is not known.");
            return remaining;
        }
        for (RoutingOperation step : routingOperations.findByRoutingIdOrderBySequence(routing.get().getId())) {
            double minutes = step.getSetupMinutes().add(step.getRunMinutes().multiply(order.getQuantity()))
                    .doubleValue();
            remaining.merge(step.getWorkCenterId(), minutes, Double::sum);
        }
        return remaining;
    }

    private static double capacity(List<ShiftCalendar> shifts, LocalDate day, int units,
            Map<LocalDate, Double> plannedDown) {
        return Math.max(ProductionCalendar.workingMinutes(shifts, day) * (double) units
                - plannedDown.getOrDefault(day, 0.0), 0);
    }

    private static BigDecimal minutes(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static String hours(double minutes) {
        return BigDecimal.valueOf(minutes / 60).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
