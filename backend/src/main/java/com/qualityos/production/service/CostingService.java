package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.CostingDtos.CostSummary;
import com.qualityos.production.dto.CostingDtos.CostSummaryRow;
import com.qualityos.production.dto.CostingDtos.CostTotals;
import com.qualityos.production.dto.CostingDtos.MaterialCost;
import com.qualityos.production.dto.CostingDtos.OperationCost;
import com.qualityos.production.dto.CostingDtos.WorkOrderCost;
import com.qualityos.production.entity.FinishedGoodsReceipt;
import com.qualityos.production.entity.MaterialMovement;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderMaterial;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.FinishedGoodsReceiptRepository;
import com.qualityos.production.repository.MaterialMovementRepository;
import com.qualityos.production.repository.WorkCenterRepository;
import com.qualityos.production.repository.WorkOrderMaterialRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.UnitPrice;
import com.qualityos.purchaseorder.service.PurchaseOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planned against actual production cost, from data already recorded: material from the purchase price of the lots
 * issued, and labour, machine and overhead from running time and each work centre's hourly rates. Nothing is guessed:
 * a missing price or rate is listed, and the cost is marked incomplete. See docs/MANUFACTURING.md.
 */
@Service
public class CostingService {

    private static final int MONEY = 2;
    private static final int MAX_SUMMARY_DAYS = 366;
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal HOUR = BigDecimal.valueOf(3600);
    private static final Set<WorkOrder.Status> CLOSED = EnumSet.of(WorkOrder.Status.COMPLETED,
            WorkOrder.Status.PARTIALLY_COMPLETED);

    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final WorkOrderMaterialRepository lines;
    private final MaterialMovementRepository movements;
    private final FinishedGoodsReceiptRepository receipts;
    private final WorkCenterRepository centres;
    private final ReworkService reworks;
    private final MaterialReceiptService lots;
    private final PurchaseOrderService purchaseOrders;
    private final MaterialService materials;
    private final ProductService products;
    private final ProductionCalendar calendar;
    private final FacilityScope scope;

    @SuppressWarnings("java:S107")
    CostingService(
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            WorkOrderMaterialRepository lines,
            MaterialMovementRepository movements,
            FinishedGoodsReceiptRepository receipts,
            WorkCenterRepository centres,
            ReworkService reworks,
            MaterialReceiptService lots,
            PurchaseOrderService purchaseOrders,
            MaterialService materials,
            ProductService products,
            ProductionCalendar calendar,
            FacilityScope scope) {
        this.workOrders = workOrders;
        this.operations = operations;
        this.lines = lines;
        this.movements = movements;
        this.receipts = receipts;
        this.centres = centres;
        this.reworks = reworks;
        this.lots = lots;
        this.purchaseOrders = purchaseOrders;
        this.materials = materials;
        this.products = products;
        this.calendar = calendar;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public WorkOrderCost ofOrder(UUID id) {
        WorkOrder order = workOrders.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(WorkOrderService.ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), WorkOrderService.ENTITY, order.getWorkOrderNumber());
        if (order.getReleasedAt() == null) {
            throw new BusinessRuleException("Work order %s is not released, so its materials and operations are not "
                    + "fixed yet.".formatted(order.getWorkOrderNumber()));
        }
        return cost(order, calendar.currency());
    }

    /** Orders closed in the period, in one facility or every facility the caller may see. */
    @Transactional(readOnly = true)
    public CostSummary summary(LocalDate from, LocalDate to, UUID facilityId) {
        ProductionCalendar.requirePeriod(from, to, MAX_SUMMARY_DAYS);
        if (facilityId != null) {
            scope.requireAllowed(facilityId, "Facility", facilityId.toString());
        }
        ZoneId zone = calendar.zone();
        String currency = calendar.currency();
        List<WorkOrder> closed = workOrders.findByStatusInAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(CLOSED,
                calendar.startOf(from, zone), calendar.startOf(to.plusDays(1), zone)).stream()
                .filter(order -> facilityId == null ? scope.allows(order.getFacilityId())
                        : facilityId.equals(order.getFacilityId()))
                .sorted(Comparator.comparing(WorkOrder::getCompletedAt))
                .toList();
        Map<UUID, ProductReference> productRefs = products.references(
                closed.stream().map(WorkOrder::getProductId).collect(Collectors.toSet()));
        List<CostSummaryRow> rows = new ArrayList<>();
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        boolean complete = true;
        for (WorkOrder order : closed) {
            WorkOrderCost cost = cost(order, currency);
            ProductReference product = productRefs.get(order.getProductId());
            rows.add(new CostSummaryRow(order.getId(), order.getWorkOrderNumber(),
                    product == null ? null : product.code(), order.getStatus(), order.getCompletedAt(),
                    order.getQuantity(), cost.receivedQuantity(), cost.planned().total(), cost.actual().total(),
                    cost.planned().unit(), cost.actual().unit(), cost.complete()));
            planned = planned.add(cost.planned().total());
            actual = actual.add(cost.actual().total());
            complete = complete && cost.complete();
        }
        return new CostSummary(from, to, currency, rows, money(planned), money(actual), complete);
    }

    // Working it out

    private WorkOrderCost cost(WorkOrder order, String currency) {
        Set<String> missing = new LinkedHashSet<>();
        Instant now = Instant.now();
        List<WorkOrderOperation> steps = operations.findByWorkOrderIdOrderBySequence(order.getId()).stream()
                .filter(step -> step.getStatus() != WorkOrderOperation.Status.CANCELLED).toList();
        Map<UUID, WorkCenter> centreById = centres.findAllById(steps.stream().map(WorkOrderOperation::getWorkCenterId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(WorkCenter::getId, c -> c));

        // Material
        List<WorkOrderMaterial> required = lines.findByWorkOrderIdOrderByLineNumber(order.getId());
        List<MaterialMovement> moved = movements.findByWorkOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .filter(m -> m.getMovementType() == MaterialMovement.Type.ISSUED
                        || m.getMovementType() == MaterialMovement.Type.RETURNED)
                .toList();
        Map<UUID, UnitPrice> lotPrices = lots.lotPrices(moved.stream().map(MaterialMovement::getLotId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, List<UnitPrice>> averages = purchaseOrders.averagePrices(required.stream()
                .map(WorkOrderMaterial::getMaterialId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, MaterialReference> materialRefs = materials.references(required.stream()
                .map(WorkOrderMaterial::getMaterialId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, ProductReference> productRefs = products.references(required.stream()
                .map(WorkOrderMaterial::getComponentProductId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<MaterialCost> materialCosts = new ArrayList<>();
        BigDecimal plannedMaterial = BigDecimal.ZERO;
        BigDecimal actualMaterial = BigDecimal.ZERO;
        for (WorkOrderMaterial line : required) {
            String code = line.getMaterialId() != null
                    ? Optional.ofNullable(materialRefs.get(line.getMaterialId())).map(MaterialReference::code).orElse("?")
                    : Optional.ofNullable(productRefs.get(line.getComponentProductId())).map(ProductReference::code)
                            .orElse("?");
            String name = line.getMaterialId() != null
                    ? Optional.ofNullable(materialRefs.get(line.getMaterialId())).map(MaterialReference::name).orElse(null)
                    : Optional.ofNullable(productRefs.get(line.getComponentProductId())).map(ProductReference::name)
                            .orElse(null);
            BigDecimal plannedCost = null;
            if (line.getMaterialId() != null) {
                plannedCost = averages.getOrDefault(line.getMaterialId(), List.of()).stream()
                        .map(price -> priced(line.getRequiredQuantity(), line.getUomId(), price))
                        .filter(Objects::nonNull).findFirst().orElse(null);
            }
            if (plannedCost == null) {
                missing.add(line.getMaterialId() != null
                        ? "%s has no purchase price to plan with.".formatted(code)
                        : "Sub-assembly %s is not costed.".formatted(code));
            } else {
                plannedMaterial = plannedMaterial.add(plannedCost);
            }
            BigDecimal issued = BigDecimal.ZERO;
            BigDecimal spent = BigDecimal.ZERO;
            boolean spentKnown = true;
            for (MaterialMovement movement : moved) {
                if (!line.getId().equals(movement.getWorkOrderMaterialId())) {
                    continue;
                }
                BigDecimal sign = movement.getMovementType() == MaterialMovement.Type.RETURNED ? BigDecimal.ONE.negate()
                        : BigDecimal.ONE;
                BigDecimal inLine = convert(movement.getQuantity(), movement.getUomId(), line.getUomId());
                issued = issued.add(inLine == null ? BigDecimal.ZERO : inLine.multiply(sign));
                UnitPrice price = movement.getLotId() == null ? null : lotPrices.get(movement.getLotId());
                BigDecimal value = price == null ? null : priced(movement.getQuantity(), movement.getUomId(), price);
                if (value == null) {
                    spentKnown = false;
                    missing.add(movement.getLotId() == null
                            ? "Sub-assembly batches issued to %s are not costed.".formatted(code)
                            : "A lot of %s issued has no purchase price.".formatted(code));
                } else {
                    spent = spent.add(value.multiply(sign));
                }
            }
            actualMaterial = actualMaterial.add(spent);
            materialCosts.add(new MaterialCost(line.getLineNumber(), code, name, line.getUomId(),
                    line.getRequiredQuantity(), plannedCost == null ? null : money(plannedCost), issued,
                    spentKnown ? money(spent) : null));
        }

        // Labour, machine and overhead, per operation
        List<OperationCost> operationCosts = new ArrayList<>();
        BigDecimal[] planned = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] actual = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (WorkOrderOperation step : steps) {
            WorkCenter centre = centreById.get(step.getWorkCenterId());
            BigDecimal plannedHours = step.getPlannedSetupMinutes().add(step.getPlannedRunMinutes())
                    .divide(SIXTY, 6, RoundingMode.HALF_UP);
            BigDecimal actualHours = BigDecimal.valueOf(step.runningSecondsAt(now)).divide(HOUR, 6,
                    RoundingMode.HALF_UP);
            BigDecimal[] rates = {centre.getLabourRate(), centre.getMachineRate(), centre.getOverheadRate()};
            String[] names = {"labour", "machine", "overhead"};
            BigDecimal stepPlanned = BigDecimal.ZERO;
            BigDecimal stepActual = BigDecimal.ZERO;
            for (int i = 0; i < 3; i++) {
                if (rates[i] == null) {
                    missing.add("Work centre %s has no %s rate.".formatted(centre.getCode(), names[i]));
                    continue;
                }
                planned[i] = planned[i].add(plannedHours.multiply(rates[i]));
                actual[i] = actual[i].add(actualHours.multiply(rates[i]));
                stepPlanned = stepPlanned.add(plannedHours.multiply(rates[i]));
                stepActual = stepActual.add(actualHours.multiply(rates[i]));
            }
            operationCosts.add(new OperationCost(step.getSequence(), step.getName(), centre.getCode(),
                    plannedHours.setScale(2, RoundingMode.HALF_UP), actualHours.setScale(2, RoundingMode.HALF_UP),
                    money(stepPlanned), money(stepActual)));
        }

        // Totals, and what scrap and rework cost of them
        Optional<FinishedGoodsReceipt> receipt = receipts.findByWorkOrderId(order.getId());
        BigDecimal received = receipt.map(FinishedGoodsReceipt::getQuantity).orElse(null);
        List<ReworkOrder> closedRework = reworks.ofOrder(order.getId()).stream()
                .filter(rework -> rework.getStatus() == ReworkOrder.Status.CLOSED).toList();
        BigDecimal scrapped = steps.stream().map(WorkOrderOperation::getScrapQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(closedRework.stream().map(ReworkOrder::getScrappedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal reworked = closedRework.stream().map(ReworkOrder::getReworkedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal good = received != null ? received
                : steps.isEmpty() ? BigDecimal.ZERO : steps.getLast().getGoodQuantity();
        BigDecimal made = good.add(scrapped);
        BigDecimal plannedTotal = plannedMaterial.add(planned[0]).add(planned[1]).add(planned[2]);
        BigDecimal conversion = actual[0].add(actual[1]).add(actual[2]);
        BigDecimal actualTotal = actualMaterial.add(conversion);
        BigDecimal scrapCost = made.signum() > 0 ? actualTotal.multiply(scrapped).divide(made, 6, RoundingMode.HALF_UP)
                : null;
        BigDecimal reworkCost = made.signum() > 0 ? conversion.multiply(reworked).divide(made, 6, RoundingMode.HALF_UP)
                : null;
        CostTotals plannedTotals = new CostTotals(money(plannedMaterial), money(planned[0]), money(planned[1]),
                money(planned[2]), null, null, money(plannedTotal),
                money(plannedTotal.divide(order.getQuantity(), 6, RoundingMode.HALF_UP)));
        CostTotals actualTotals = new CostTotals(money(actualMaterial), money(actual[0]), money(actual[1]),
                money(actual[2]), scrapCost == null ? null : money(scrapCost),
                reworkCost == null ? null : money(reworkCost), money(actualTotal),
                received == null || received.signum() == 0 ? null
                        : money(actualTotal.divide(received, 6, RoundingMode.HALF_UP)));
        return new WorkOrderCost(order.getId(), order.getWorkOrderNumber(), order.getStatus(), currency,
                order.getQuantity(), received, materialCosts, operationCosts, plannedTotals, actualTotals,
                missing.isEmpty(), List.copyOf(missing));
    }

    /** Quantity in {@code uom} at a price per the price's own unit; null when the units do not convert. */
    private BigDecimal priced(BigDecimal quantity, UUID uom, UnitPrice price) {
        BigDecimal inPriceUnit = convert(quantity, uom, price.uomId());
        return inPriceUnit == null ? null : inPriceUnit.multiply(price.price());
    }

    private BigDecimal convert(BigDecimal quantity, UUID from, UUID to) {
        if (from.equals(to) || quantity.signum() == 0) {
            return quantity;
        }
        try {
            return materials.convert(quantity, from, to);
        } catch (BusinessRuleException e) {
            return null;
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY, RoundingMode.HALF_UP);
    }

}
