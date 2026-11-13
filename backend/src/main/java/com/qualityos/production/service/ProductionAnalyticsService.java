package com.qualityos.production.service;

import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.dto.FacilityDtos.ShiftCalendar;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.CompletionDtos.MaterialVariance;
import com.qualityos.production.dto.DowntimeDtos.GroupBy;
import com.qualityos.production.dto.ProductionAnalyticsDtos.Efficiency;
import com.qualityos.production.dto.ProductionAnalyticsDtos.MachineOee;
import com.qualityos.production.dto.ProductionAnalyticsDtos.MaterialVarianceSummary;
import com.qualityos.production.dto.ProductionAnalyticsDtos.ProductOutput;
import com.qualityos.production.dto.ProductionAnalyticsDtos.ProductionAnalytics;
import com.qualityos.production.dto.ProductionAnalyticsDtos.QualityRates;
import com.qualityos.production.dto.ProductionAnalyticsDtos.StatusCount;
import com.qualityos.production.entity.DowntimeRecord;
import com.qualityos.production.entity.FinishedGoodsReceipt;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.OperationEvent;
import com.qualityos.production.entity.OperationReport;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.FinishedGoodsReceiptRepository;
import com.qualityos.production.repository.MachineRepository;
import com.qualityos.production.repository.OperationEventRepository;
import com.qualityos.production.repository.OperationReportRepository;
import com.qualityos.production.repository.WorkCenterRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Production analytics and OEE for a period, from what the shop floor recorded: operation events give running time,
 * operation reports give units, shifts give working time, and downtime records give planned and unplanned stops.
 * Every figure says what it is made of; a figure whose data is missing is null with a note, not zero. See
 * docs/MANUFACTURING.md.
 */
@Service
public class ProductionAnalyticsService {

    private static final int MAX_DAYS = 366;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Set<WorkOrder.Status> CLOSED = EnumSet.of(WorkOrder.Status.COMPLETED,
            WorkOrder.Status.PARTIALLY_COMPLETED);

    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final OperationEventRepository events;
    private final OperationReportRepository reports;
    private final FinishedGoodsReceiptRepository receipts;
    private final WorkCenterRepository centres;
    private final MachineRepository machines;
    private final DowntimeService downtime;
    private final CostingService costing;
    private final ProductionCalendar calendar;
    private final ProductService products;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final JsonMapper json;

    @SuppressWarnings("java:S107")
    ProductionAnalyticsService(
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            OperationEventRepository events,
            OperationReportRepository reports,
            FinishedGoodsReceiptRepository receipts,
            WorkCenterRepository centres,
            MachineRepository machines,
            DowntimeService downtime,
            CostingService costing,
            ProductionCalendar calendar,
            ProductService products,
            FacilityService facilities,
            FacilityScope scope,
            JsonMapper json) {
        this.workOrders = workOrders;
        this.operations = operations;
        this.events = events;
        this.reports = reports;
        this.receipts = receipts;
        this.centres = centres;
        this.machines = machines;
        this.downtime = downtime;
        this.costing = costing;
        this.calendar = calendar;
        this.products = products;
        this.facilities = facilities;
        this.scope = scope;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ProductionAnalytics analytics(LocalDate from, LocalDate to, UUID facilityId, boolean withCost) {
        ProductionCalendar.requirePeriod(from, to, MAX_DAYS);
        Collection<UUID> facilityIds;
        if (facilityId != null) {
            scope.requireAllowed(facilityId, "Facility", facilityId.toString());
            facilityIds = List.of(facilityId);
        } else {
            facilityIds = scope.restriction().map(allowed -> (Collection<UUID>) allowed).orElseGet(facilities::allIds);
        }
        Set<UUID> facilitySet = Set.copyOf(facilityIds);
        ZoneId zone = calendar.zone();
        Instant start = calendar.startOf(from, zone);
        Instant end = calendar.startOf(to.plusDays(1), zone);
        Instant now = Instant.now();
        Instant until = end.isAfter(now) ? now : end;

        List<WorkCenter> centreList = facilityIds.isEmpty() ? List.of()
                : centres.findByFacilityIdInAndArchivedAtIsNullOrderByCode(facilityIds);
        Map<UUID, WorkCenter> centreById = centreList.stream().collect(Collectors.toMap(WorkCenter::getId, c -> c));
        List<Machine> machineList = centreById.isEmpty() ? List.of()
                : machines.findByWorkCenterIdIn(centreById.keySet()).stream()
                        .filter(machine -> machine.getStatus() != Machine.Status.INACTIVE)
                        .sorted(Comparator.comparing(Machine::getCode)).toList();

        // Units reported in the period, and the operations and orders they belong to
        List<OperationReport> reported = centreById.isEmpty() ? List.of()
                : reports.atCentresBetween(centreById.keySet(), start, end);
        List<OperationEvent> timeline = centreById.isEmpty() ? List.of()
                : events.atCentresBefore(centreById.keySet(), end);
        Set<UUID> operationIds = new HashSet<>();
        reported.forEach(report -> operationIds.add(report.getOperationId()));
        Map<UUID, WorkOrderOperation> operationById = operations.findAllById(operationIds).stream()
                .collect(Collectors.toMap(WorkOrderOperation::getId, o -> o));
        Map<UUID, WorkOrder> orderById = workOrders.findAllById(operationById.values().stream()
                .map(WorkOrderOperation::getWorkOrderId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(WorkOrder::getId, o -> o));

        QualityRates quality = qualityRates(reported);
        Efficiency efficiency = efficiency(centreById.keySet(), start, end);

        // Machines: shift time, planned and unplanned downtime, running time, units
        Map<UUID, List<ShiftCalendar>> shifts = calendar.shifts(facilityIds);
        List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();
        List<DowntimeRecord> stops = facilityIds.isEmpty() ? List.of()
                : facilityId != null ? downtime.overlapping(facilityId, start, end)
                        : facilityIds.stream().flatMap(id -> downtime.overlapping(id, start, end).stream()).toList();
        Map<UUID, Double> running = runningMinutes(timeline, start, until);
        Map<UUID, double[]> units = new HashMap<>();
        for (OperationReport report : reported) {
            WorkOrderOperation step = operationById.get(report.getOperationId());
            if (step == null || step.getMachineId() == null) {
                continue;
            }
            WorkOrder order = orderById.get(step.getWorkOrderId());
            double total = report.getGoodQuantity().add(report.getRejectedQuantity()).add(report.getScrapQuantity())
                    .doubleValue();
            double idealPerUnit = order == null || order.getQuantity().signum() == 0 ? 0
                    : step.getPlannedRunMinutes().doubleValue() / order.getQuantity().doubleValue();
            double[] sums = units.computeIfAbsent(step.getMachineId(), ignored -> new double[3]);
            sums[0] += total;
            sums[1] += report.getGoodQuantity().doubleValue();
            sums[2] += idealPerUnit * total;
        }
        List<MachineOee> machineRows = new ArrayList<>();
        double[] all = new double[8];
        for (Machine machine : machineList) {
            WorkCenter centre = centreById.get(machine.getWorkCenterId());
            List<ShiftCalendar> calendarShifts = shifts.getOrDefault(centre.getFacilityId(), List.of());
            double shift = days.stream().mapToDouble(day -> ProductionCalendar.workingMinutes(calendarShifts, day))
                    .sum();
            double planned = 0;
            double unplanned = 0;
            for (DowntimeRecord stop : stops) {
                boolean here = machine.getId().equals(stop.getMachineId())
                        || stop.getMachineId() == null && centre.getId().equals(stop.getWorkCenterId());
                if (!here) {
                    continue;
                }
                double minutes = stop.minutesWithin(start, end, now);
                if (stop.isPlanned()) {
                    planned += minutes;
                } else {
                    unplanned += minutes;
                }
            }
            double[] made = units.getOrDefault(machine.getId(), new double[3]);
            double ran = running.getOrDefault(machine.getId(), 0.0);
            machineRows.add(oee(machine.getId(), machine.getCode(), centre.getCode(), shift, planned, ran, unplanned,
                    made));
            double[] row = {shift, planned, ran, unplanned, made[0], made[1], made[2], 0};
            for (int i = 0; i < 7; i++) {
                all[i] += row[i];
            }
        }
        MachineOee overall = oee(null, null, null, all[0], all[1], all[2], all[3],
                new double[] {all[4], all[5], all[6]});

        // Orders: output, planned against actual, status, material variance
        List<FinishedGoodsReceipt> inPeriod = receipts.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(start, end);
        Map<UUID, WorkOrder> receiptOrders = workOrders.findAllById(inPeriod.stream()
                .map(FinishedGoodsReceipt::getWorkOrderId).collect(Collectors.toSet())).stream()
                .filter(order -> facilitySet.contains(order.getFacilityId()))
                .collect(Collectors.toMap(WorkOrder::getId, o -> o));
        List<FinishedGoodsReceipt> received = inPeriod.stream()
                .filter(receipt -> receiptOrders.containsKey(receipt.getWorkOrderId())).toList();
        List<WorkOrder> closed = workOrders.findByStatusInAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(CLOSED,
                start, end).stream().filter(order -> facilitySet.contains(order.getFacilityId())).toList();
        Map<UUID, BigDecimal> receivedByOrder = new HashMap<>();
        closed.forEach(order -> receipts.findByWorkOrderId(order.getId())
                .ifPresent(receipt -> receivedByOrder.put(order.getId(), receipt.getQuantity())));
        Set<UUID> productIds = new HashSet<>();
        receiptOrders.values().forEach(order -> productIds.add(order.getProductId()));
        closed.forEach(order -> productIds.add(order.getProductId()));
        Map<UUID, ProductReference> productRefs = products.references(productIds);
        List<ProductOutput> production = byProduct(received.stream().map(receipt -> receiptOrders
                .get(receipt.getWorkOrderId())).toList(), order -> received.stream()
                        .filter(r -> r.getWorkOrderId().equals(order.getId())).map(FinishedGoodsReceipt::getQuantity)
                        .findFirst().orElse(BigDecimal.ZERO), productRefs);
        List<ProductOutput> plannedVersusActual = byProduct(closed,
                order -> receivedByOrder.getOrDefault(order.getId(), BigDecimal.ZERO), productRefs);
        long completed = closed.stream().filter(order -> order.getStatus() == WorkOrder.Status.COMPLETED).count();
        long onTime = closed.stream().filter(order -> !LocalDate.ofInstant(order.getCompletedAt(), zone)
                .isAfter(order.getPlannedEnd())).count();
        List<StatusCount> statuses = facilityIds.isEmpty() ? List.of()
                : workOrders.statusCounts(facilityIds).stream()
                        .map(row -> new StatusCount(((WorkOrder.Status) row[0]).name(), (Long) row[1]))
                        .sorted(Comparator.comparing(StatusCount::status)).toList();

        return new ProductionAnalytics(from, to, zone.getId(), facilityId, production, plannedVersusActual,
                completed, closed.size() - completed, onTime, statuses, quality, efficiency,
                materialVariance(received), machineRows, overall,
                downtime.summary(from, to, facilityId, GroupBy.CATEGORY).rows(),
                withCost ? costing.summary(from, to, facilityId) : null);
    }

    // Figures

    private static QualityRates qualityRates(List<OperationReport> reported) {
        BigDecimal good = sum(reported, OperationReport::getGoodQuantity);
        BigDecimal rejected = sum(reported, OperationReport::getRejectedQuantity);
        BigDecimal scrap = sum(reported, OperationReport::getScrapQuantity);
        BigDecimal rework = sum(reported, OperationReport::getReworkQuantity);
        BigDecimal processed = good.add(rejected).add(scrap);
        return new QualityRates(processed, good, rejected, scrap, rework, percent(rejected, processed),
                percent(scrap, processed), percent(rework, processed));
    }

    private Efficiency efficiency(Collection<UUID> centreIds, Instant start, Instant end) {
        List<WorkOrderOperation> done = centreIds.isEmpty() ? List.of()
                : operations.findByWorkCenterIdInAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(centreIds,
                        start, end).stream().filter(step -> step.getStatus() == WorkOrderOperation.Status.COMPLETED)
                        .toList();
        BigDecimal planned = done.stream().map(step -> step.getPlannedSetupMinutes().add(step.getPlannedRunMinutes()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = BigDecimal.valueOf(done.stream().mapToLong(WorkOrderOperation::getRunningSeconds).sum())
                .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
        return new Efficiency(done.size(), planned.setScale(1, RoundingMode.HALF_UP), actual,
                percent(planned, actual));
    }

    /**
     * Running minutes per machine inside [start, until): from an event moving an operation into progress to the next
     * moving it out, on the machine the operation had then.
     */
    private static Map<UUID, Double> runningMinutes(List<OperationEvent> timeline, Instant start, Instant until) {
        Map<UUID, Double> running = new HashMap<>();
        UUID operation = null;
        Instant openedAt = null;
        UUID machine = null;
        for (OperationEvent event : timeline) {
            if (!event.getOperationId().equals(operation)) {
                close(running, machine, openedAt, until, start, until);
                operation = event.getOperationId();
                openedAt = null;
                machine = null;
            }
            boolean intoProgress = event.getToStatus() == WorkOrderOperation.Status.IN_PROGRESS
                    && event.getFromStatus() != WorkOrderOperation.Status.IN_PROGRESS;
            boolean outOfProgress = event.getFromStatus() == WorkOrderOperation.Status.IN_PROGRESS
                    && event.getToStatus() != WorkOrderOperation.Status.IN_PROGRESS;
            if (intoProgress && openedAt == null) {
                openedAt = event.getCreatedAt();
                machine = event.getMachineId();
            } else if (outOfProgress && openedAt != null) {
                close(running, machine, openedAt, event.getCreatedAt(), start, until);
                openedAt = null;
                machine = null;
            }
        }
        close(running, machine, openedAt, until, start, until);
        return running;
    }

    private static void close(Map<UUID, Double> running, UUID machine, Instant openedAt, Instant closedAt,
            Instant start, Instant until) {
        if (machine == null || openedAt == null) {
            return;
        }
        Instant a = openedAt.isAfter(start) ? openedAt : start;
        Instant b = closedAt.isBefore(until) ? closedAt : until;
        if (b.isAfter(a)) {
            running.merge(machine, Duration.between(a, b).toSeconds() / 60.0, Double::sum);
        }
    }

    /** @param made units reported, good units, and ideal minutes for them */
    private static MachineOee oee(UUID id, String code, String centre, double shift, double plannedDown, double ran,
            double unplannedDown, double[] made) {
        List<String> notes = new ArrayList<>();
        double plannedProduction = Math.max(shift - plannedDown, 0);
        BigDecimal availability = null;
        BigDecimal utilisation = null;
        if (shift <= 0) {
            notes.add("No shifts cover the period, so availability and utilisation cannot be worked out.");
        } else {
            utilisation = percent(ran, shift);
            if (plannedProduction > 0) {
                double ratio = Math.min(ran / plannedProduction, 1);
                if (ran > plannedProduction) {
                    notes.add("It ran longer than its planned production time; availability is shown as 100%.");
                }
                availability = percent(ratio, 1);
            } else {
                notes.add("Planned downtime covers all its shift time.");
            }
        }
        BigDecimal performance = null;
        if (ran <= 0) {
            notes.add("It did not run in the period.");
        } else if (made[0] <= 0) {
            notes.add("Nothing was reported on it, so performance cannot be worked out.");
        } else {
            performance = percent(made[2], ran);
        }
        BigDecimal quality = made[0] > 0 ? percent(made[1], made[0]) : null;
        if (made[0] > 0 && made[2] <= 0) {
            notes.add("Its operations have no run time per unit, so performance is not known.");
            performance = null;
        }
        BigDecimal oee = availability == null || performance == null || quality == null ? null
                : availability.multiply(performance).multiply(quality)
                        .divide(HUNDRED.multiply(HUNDRED), 1, RoundingMode.HALF_UP);
        return new MachineOee(id, code, centre, DowntimeService.minutes(shift), DowntimeService.minutes(plannedDown),
                DowntimeService.minutes(plannedProduction), DowntimeService.minutes(ran),
                DowntimeService.minutes(unplannedDown), utilisation, availability, performance, quality, oee, notes);
    }

    private MaterialVarianceSummary materialVariance(List<FinishedGoodsReceipt> received) {
        long lines = 0;
        long outside = 0;
        BigDecimal absolute = BigDecimal.ZERO;
        long withPercent = 0;
        for (FinishedGoodsReceipt receipt : received) {
            List<MaterialVariance> variances = json.readValue(receipt.getMaterialVariances(),
                    new TypeReference<List<MaterialVariance>>() { });
            for (MaterialVariance variance : variances) {
                lines++;
                if (variance.outside()) {
                    outside++;
                }
                if (variance.percent() != null) {
                    absolute = absolute.add(variance.percent().abs());
                    withPercent++;
                }
            }
        }
        return new MaterialVarianceSummary(received.size(), lines, outside, withPercent == 0 ? null
                : absolute.divide(BigDecimal.valueOf(withPercent), 1, RoundingMode.HALF_UP));
    }

    private static List<ProductOutput> byProduct(List<WorkOrder> orders, Function<WorkOrder, BigDecimal> received,
            Map<UUID, ProductReference> productRefs) {
        Map<UUID, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<UUID, Long> counts = new HashMap<>();
        Map<UUID, UUID> unitOf = new HashMap<>();
        for (WorkOrder order : orders.stream().filter(Objects::nonNull).toList()) {
            BigDecimal[] sum = sums.computeIfAbsent(order.getProductId(),
                    ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(order.getQuantity());
            sum[1] = sum[1].add(received.apply(order));
            counts.merge(order.getProductId(), 1L, Long::sum);
            unitOf.putIfAbsent(order.getProductId(), order.getUomId());
        }
        return sums.entrySet().stream().map(entry -> {
            ProductReference product = productRefs.get(entry.getKey());
            return new ProductOutput(entry.getKey(), product == null ? null : product.code(),
                    product == null ? null : product.name(), unitOf.get(entry.getKey()), entry.getValue()[0],
                    entry.getValue()[1], counts.get(entry.getKey()));
        }).sorted(Comparator.comparing(ProductOutput::productCode, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null
                : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(double part, double whole) {
        return whole <= 0 ? null : BigDecimal.valueOf(part * 100 / whole).setScale(1, RoundingMode.HALF_UP);
    }
}
