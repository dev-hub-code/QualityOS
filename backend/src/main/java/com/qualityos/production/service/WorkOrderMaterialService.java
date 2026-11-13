package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ConsumptionResponse;
import com.qualityos.production.dto.WorkOrderMaterialDtos.IssueRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.MaterialLine;
import com.qualityos.production.dto.WorkOrderMaterialDtos.MaterialsResponse;
import com.qualityos.production.dto.WorkOrderMaterialDtos.MovementResponse;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ReservationResponse;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ReserveRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ReturnRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.SourceOption;
import com.qualityos.production.entity.MaterialMovement;
import com.qualityos.production.entity.MaterialReservation;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderMaterial;
import com.qualityos.production.repository.MaterialMovementRepository;
import com.qualityos.production.repository.MaterialReservationRepository;
import com.qualityos.production.repository.WorkOrderMaterialRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumeRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumptionRef;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.service.ProductionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A work order's materials against the stock the inventory module keeps. Reserving sets stock aside on the lot, so
 * nothing else can take it. Issuing records consumption into the order's production batch, which is the stock
 * movement and the genealogy at once. Returning gives back what was not used. Nothing here writes a balance directly.
 *
 * <p>Each change locks the work order, so requests for the same order are applied one after another, and the lot, so
 * two orders cannot set aside or take the same stock. A request with an {@code Idempotency-Key} already applied to the
 * order is answered without being applied again.
 */
@Service
public class WorkOrderMaterialService {

    private static final String ENTITY = WorkOrderService.ENTITY;
    private static final String MATERIAL = "MATERIAL";
    private static final String PRODUCT = "PRODUCT";
    private static final String LOT = "LOT";
    private static final String BATCH = "BATCH";
    private static final int MAX_KEY = 100;
    private static final Set<WorkOrder.Status> RESERVABLE = EnumSet.of(
            WorkOrder.Status.RELEASED, WorkOrder.Status.IN_PROGRESS);

    private final WorkOrderRepository workOrders;
    private final WorkOrderMaterialRepository requirements;
    private final MaterialReservationRepository reservations;
    private final MaterialMovementRepository movements;
    private final MaterialReceiptService receipts;
    private final ProductionService production;
    private final MaterialService materials;
    private final ProductService products;
    private final ManufacturingSettingsService settings;
    private final FacilityScope scope;
    private final RevisionSupport support;
    private final AuditService audit;

    WorkOrderMaterialService(
            WorkOrderRepository workOrders,
            WorkOrderMaterialRepository requirements,
            MaterialReservationRepository reservations,
            MaterialMovementRepository movements,
            MaterialReceiptService receipts,
            ProductionService production,
            MaterialService materials,
            ProductService products,
            ManufacturingSettingsService settings,
            FacilityScope scope,
            RevisionSupport support,
            AuditService audit) {
        this.workOrders = workOrders;
        this.requirements = requirements;
        this.reservations = reservations;
        this.movements = movements;
        this.receipts = receipts;
        this.production = production;
        this.materials = materials;
        this.products = products;
        this.settings = settings;
        this.scope = scope;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MaterialsResponse get(UUID id) {
        WorkOrder order = workOrders.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        return view(order);
    }

    /** Sets aside stock on one lot for one material line, up to what the line still needs. */
    @Transactional
    public MaterialsResponse reserve(UUID id, ReserveRequest request, String key) {
        WorkOrder order = lock(id);
        if (repeated(order, key)) {
            return view(order);
        }
        requireStatus(order, RESERVABLE, "reserve stock for it");
        WorkOrderMaterial line = line(order, request.lineNumber());
        if (line.getMaterialId() == null) {
            throw new BusinessRuleException("Line %d is a sub-assembly. It is issued from its batches, not reserved."
                    .formatted(line.getLineNumber()));
        }
        Coverage coverage = coverage(order).get(line.getId());
        if (request.quantity().compareTo(coverage.outstanding()) > 0) {
            throw new BusinessRuleException("Line %d needs only %s more.".formatted(
                    line.getLineNumber(), plain(coverage.outstanding())));
        }
        LotView lot = lotFor(order, line, request.lotId());
        BigDecimal inLot = convert(request.quantity(), line.getUomId(), lot.uomId());
        receipts.reserve(lot.itemId(), inLot);
        MaterialReservation reservation = reservations.saveAndFlush(
                new MaterialReservation(id, line.getId(), lot.itemId(), inLot, lot.uomId()));
        movements.saveAndFlush(new MaterialMovement(id, line.getId(), MaterialMovement.Type.RESERVED, lot.itemId(),
                null, inLot, lot.uomId(), reservation.getId(), null, null, key));
        audit.record("WORK_ORDER_MATERIAL_RESERVED", ENTITY, id, null, Map.of(
                "line", line.getLineNumber(), "lot", lot.internalBatch(), "quantity", inLot.toPlainString()));
        return view(order);
    }

    /** Reserves what each material line still needs from the oldest free lots in the facility, as far as they go. */
    @Transactional
    public MaterialsResponse reserveAvailable(UUID id, String key) {
        WorkOrder order = lock(id);
        if (repeated(order, key)) {
            return view(order);
        }
        requireStatus(order, RESERVABLE, "reserve stock for it");
        reserveAvailable(order, key);
        return view(order);
    }

    /** For release: reserves what it can and leaves the rest showing as outstanding. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveAvailable(WorkOrder order, String key) {
        Map<UUID, Coverage> coverage = coverage(order);
        List<WorkOrderMaterial> lines = requirements.findByWorkOrderIdOrderByLineNumber(order.getId()).stream()
                .filter(line -> line.getMaterialId() != null)
                .toList();
        Map<UUID, List<LotView>> lotsByMaterial = receipts.freeLots(
                        lines.stream().map(WorkOrderMaterial::getMaterialId).collect(Collectors.toSet()),
                        order.getFacilityId()).stream()
                .collect(Collectors.groupingBy(LotView::materialId, LinkedHashMap::new, Collectors.toList()));
        String pendingKey = key;
        int made = 0;
        for (WorkOrderMaterial line : lines) {
            BigDecimal remaining = coverage.get(line.getId()).outstanding();
            for (LotView lot : lotsByMaterial.getOrDefault(line.getMaterialId(), List.of())) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal got = receipts.reserveUpTo(lot.itemId(), convert(remaining, line.getUomId(), lot.uomId()));
                if (got.signum() == 0) {
                    continue;
                }
                MaterialReservation reservation = reservations.saveAndFlush(
                        new MaterialReservation(order.getId(), line.getId(), lot.itemId(), got, lot.uomId()));
                movements.saveAndFlush(new MaterialMovement(order.getId(), line.getId(), MaterialMovement.Type.RESERVED,
                        lot.itemId(), null, got, lot.uomId(), reservation.getId(), null, null, pendingKey));
                pendingKey = null;
                made++;
                remaining = remaining.subtract(convert(got, lot.uomId(), line.getUomId())).max(BigDecimal.ZERO);
            }
        }
        if (made > 0) {
            audit.record("WORK_ORDER_MATERIAL_RESERVED", ENTITY, order.getId(), null, Map.of("reservations", made));
        }
    }

    /** Gives back what a reservation still holds to the lot's free stock. */
    @Transactional
    public MaterialsResponse unreserve(UUID id, UUID reservationId, String reason) {
        WorkOrder order = lock(id);
        MaterialReservation reservation = reservations.findById(reservationId)
                .filter(found -> found.getWorkOrderId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
        if (reservation.getOpenQuantity().signum() == 0) {
            throw new BusinessRuleException("This reservation has nothing left to release.");
        }
        release(order, reservation, reason == null || reason.isBlank() ? null : reason.trim());
        return view(order);
    }

    /** When the order is cancelled, everything it still holds goes back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAll(WorkOrder order, String reason) {
        reservations.findOpen(order.getId()).forEach(reservation -> release(order, reservation, reason));
    }

    private void release(WorkOrder order, MaterialReservation reservation, String reason) {
        BigDecimal released = reservation.close();
        receipts.unreserve(reservation.getLotId(), released);
        reservations.saveAndFlush(reservation);
        movements.saveAndFlush(new MaterialMovement(order.getId(), reservation.getWorkOrderMaterialId(),
                MaterialMovement.Type.UNRESERVED, reservation.getLotId(), null, released, reservation.getUomId(),
                reservation.getId(), null, reason, null));
        audit.record("WORK_ORDER_MATERIAL_UNRESERVED", ENTITY, order.getId(), null,
                Map.of("quantity", released.toPlainString(), "reason", Objects.toString(reason, "")));
    }

    /**
     * Issues material to the order's batch. From a lot, the order's own reservations on it are used first and the
     * rest comes from free stock. More than the line needs is refused unless the organisation allows it.
     */
    @Transactional
    public MaterialsResponse issue(UUID id, IssueRequest request, String key) {
        WorkOrder order = lock(id);
        if (repeated(order, key)) {
            return view(order);
        }
        requireStatus(order, EnumSet.of(WorkOrder.Status.IN_PROGRESS), "take material");
        WorkOrderMaterial line = line(order, request.lineNumber());
        if ((request.lotId() == null) == (request.sourceBatchId() == null)) {
            throw new BusinessRuleException("Choose one lot or batch to issue from.");
        }
        Coverage coverage = coverage(order).get(line.getId());
        if (!settings.rules().allowOverIssue()
                && coverage.issued().add(request.quantity()).compareTo(line.getRequiredQuantity()) > 0) {
            throw new BusinessRuleException(("Line %d needs %s and %s is already issued. Issuing more than an order "
                    + "needs is switched off in Configuration.").formatted(line.getLineNumber(),
                    plain(line.getRequiredQuantity()), plain(coverage.issued())));
        }
        UUID batch = order.getProductionBatchId();
        Issue issue = new Issue(order, line, key);
        if (line.getMaterialId() != null) {
            if (request.lotId() == null) {
                throw new BusinessRuleException("Line %d is a material. Issue it from a lot.".formatted(line.getLineNumber()));
            }
            LotView lot = lotFor(order, line, request.lotId());
            BigDecimal inLot = convert(request.quantity(), line.getUomId(), lot.uomId());
            BigDecimal fromReserved = BigDecimal.ZERO;
            for (MaterialReservation reservation : reservations.findOpen(id)) {
                if (reservation.getLotId().equals(lot.itemId())
                        && reservation.getWorkOrderMaterialId().equals(line.getId())) {
                    fromReserved = fromReserved.add(reservation.use(inLot.subtract(fromReserved)));
                    reservations.saveAndFlush(reservation);
                }
            }
            if (fromReserved.signum() > 0) {
                UUID entry = production.consumeReserved(batch, lot.itemId(), fromReserved);
                issue.record(lot.itemId(), null, fromReserved, lot.uomId(), entry);
            }
            BigDecimal fromFree = inLot.subtract(fromReserved);
            if (fromFree.signum() > 0) {
                UUID entry = production.consumeEntry(batch, new ConsumeRequest(lot.itemId(), null, fromFree, null));
                issue.record(lot.itemId(), null, fromFree, lot.uomId(), entry);
            }
        } else {
            if (request.sourceBatchId() == null) {
                throw new BusinessRuleException("Line %d is a sub-assembly. Issue it from one of its batches."
                        .formatted(line.getLineNumber()));
            }
            ProductionBatchView source = production.views(List.of(request.sourceBatchId())).stream().findFirst()
                    .filter(found -> found.productId().equals(line.getComponentProductId()))
                    .orElseThrow(() -> new BusinessRuleException("That batch is not of line %d's product."
                            .formatted(line.getLineNumber())));
            requireSameFacility(order, source.facilityId());
            BigDecimal inBatch = convert(request.quantity(), line.getUomId(), source.uomId());
            UUID entry = production.consumeEntry(batch, new ConsumeRequest(null, source.id(), inBatch, null));
            issue.record(null, source.id(), inBatch, source.uomId(), entry);
        }
        audit.record("WORK_ORDER_MATERIAL_ISSUED", ENTITY, id, null, Map.of(
                "line", line.getLineNumber(), "quantity", request.quantity().toPlainString(), "entries", issue.entries));
        return view(order);
    }

    /** Gives back part or all of one consumption to where it came from. The genealogy keeps what stayed used. */
    @Transactional
    public MaterialsResponse returnMaterial(UUID id, ReturnRequest request, String key) {
        WorkOrder order = lock(id);
        if (repeated(order, key)) {
            return view(order);
        }
        requireStatus(order, EnumSet.of(WorkOrder.Status.IN_PROGRESS), "return material");
        ConsumptionRef consumption = production.activeConsumptions(order.getProductionBatchId()).stream()
                .filter(found -> found.id().equals(request.consumptionId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Consumption", request.consumptionId()));
        Sources sources = sources(List.of(consumption));
        String reason = request.reason().trim();
        production.returnConsumption(order.getProductionBatchId(), consumption.id(), request.quantity(), reason);
        WorkOrderMaterial line = lineFor(order, sources, consumption);
        movements.saveAndFlush(new MaterialMovement(id, line == null ? null : line.getId(),
                MaterialMovement.Type.RETURNED, consumption.lotId(), consumption.parentBatchId(), request.quantity(),
                sources.unitOf(consumption), null, consumption.id(), reason, key));
        audit.record("WORK_ORDER_MATERIAL_RETURNED", ENTITY, id, null,
                Map.of("quantity", request.quantity().toPlainString(), "reason", reason));
        return view(order);
    }

    /**
     * What stops production from having everything: a material line not issued or reserved in full, or a sub-assembly
     * the facility does not have enough of.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> shortfalls(WorkOrder order) {
        MaterialsResponse view = view(order);
        Map<UUID, String> units = unitCodes();
        List<String> found = new ArrayList<>();
        for (MaterialLine line : view.lines()) {
            BigDecimal covered = line.issued().add(line.reserved());
            boolean material = MATERIAL.equals(line.componentType());
            if (!material) {
                covered = covered.add(line.available());
            }
            if (covered.compareTo(line.required()) < 0) {
                String unit = units.get(line.uomId());
                found.add("%s needs %s %s, %s %s %s.".formatted(line.componentCode(), plain(line.required()), unit,
                        material ? "reserved" : "in stock", plain(covered), unit));
            }
        }
        return found;
    }

    /** Records each consumption entry an issue made; the request's key goes on the first. */
    private final class Issue {

        private final WorkOrder order;
        private final WorkOrderMaterial line;
        private String key;
        private int entries;

        Issue(WorkOrder order, WorkOrderMaterial line, String key) {
            this.order = order;
            this.line = line;
            this.key = key;
        }

        void record(UUID lot, UUID batch, BigDecimal quantity, UUID uom, UUID entry) {
            movements.saveAndFlush(new MaterialMovement(order.getId(), line.getId(), MaterialMovement.Type.ISSUED, lot,
                    batch, quantity, uom, null, entry, null, key));
            key = null;
            entries++;
        }
    }

    /** How far each line is covered, in its own unit. */
    private record Coverage(BigDecimal required, BigDecimal issued, BigDecimal reserved) {

        BigDecimal outstanding() {
            return required.subtract(issued).subtract(reserved).max(BigDecimal.ZERO);
        }
    }

    /** The lots and batches that consumptions came from, for matching them to lines. */
    private record Sources(Map<UUID, LotView> lots, Map<UUID, ProductionBatchView> batches) {

        UUID unitOf(ConsumptionRef consumption) {
            return consumption.lotId() != null ? lots.get(consumption.lotId()).uomId()
                    : batches.get(consumption.parentBatchId()).uomId();
        }

        String key(ConsumptionRef consumption) {
            return consumption.lotId() != null ? MATERIAL + ":" + lots.get(consumption.lotId()).materialId()
                    : PRODUCT + ":" + batches.get(consumption.parentBatchId()).productId();
        }

        String number(ConsumptionRef consumption) {
            return consumption.lotId() != null ? lots.get(consumption.lotId()).internalBatch()
                    : batches.get(consumption.parentBatchId()).batchNumber();
        }
    }

    private Sources sources(List<ConsumptionRef> consumptions) {
        Set<UUID> lotIds = consumptions.stream().map(ConsumptionRef::lotId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> batchIds = consumptions.stream().map(ConsumptionRef::parentBatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return new Sources(
                lotIds.isEmpty() ? Map.of() : receipts.lots(lotIds).stream()
                        .collect(Collectors.toMap(LotView::itemId, Function.identity())),
                batchIds.isEmpty() ? Map.of() : production.views(batchIds).stream()
                        .collect(Collectors.toMap(ProductionBatchView::id, Function.identity())));
    }

    private static String key(WorkOrderMaterial line) {
        return line.getMaterialId() != null ? MATERIAL + ":" + line.getMaterialId()
                : PRODUCT + ":" + line.getComponentProductId();
    }

    private WorkOrderMaterial lineFor(WorkOrder order, Sources sources, ConsumptionRef consumption) {
        String wanted = sources.key(consumption);
        return requirements.findByWorkOrderIdOrderByLineNumber(order.getId()).stream()
                .filter(line -> key(line).equals(wanted))
                .findFirst()
                .orElse(null);
    }

    /**
     * Issued counts what went into the batch and still stands, however it was recorded. Where two lines name the same
     * component, the first is filled before the next.
     */
    private Map<UUID, Coverage> coverage(WorkOrder order) {
        return coverage(order, requirements.findByWorkOrderIdOrderByLineNumber(order.getId()), consumptions(order),
                reservations.findOpen(order.getId()), null);
    }

    private List<ConsumptionRef> consumptions(WorkOrder order) {
        return order.getProductionBatchId() == null ? List.of()
                : production.activeConsumptions(order.getProductionBatchId());
    }

    private Map<UUID, Coverage> coverage(
            WorkOrder order,
            List<WorkOrderMaterial> lines,
            List<ConsumptionRef> consumptions,
            List<MaterialReservation> open,
            Map<UUID, Integer> attribution) {
        Sources sources = sources(consumptions);
        Map<UUID, BigDecimal> issued = new HashMap<>();
        for (ConsumptionRef consumption : consumptions) {
            String wanted = sources.key(consumption);
            List<WorkOrderMaterial> matching = lines.stream().filter(line -> key(line).equals(wanted)).toList();
            if (matching.isEmpty()) {
                continue;
            }
            BigDecimal left = convert(consumption.quantity(), sources.unitOf(consumption), matching.getFirst().getUomId());
            for (int i = 0; i < matching.size() && left.signum() > 0; i++) {
                WorkOrderMaterial line = matching.get(i);
                BigDecimal sofar = issued.getOrDefault(line.getId(), BigDecimal.ZERO);
                BigDecimal take = i == matching.size() - 1 ? left
                        : left.min(line.getRequiredQuantity().subtract(sofar).max(BigDecimal.ZERO));
                if (take.signum() > 0) {
                    issued.put(line.getId(), sofar.add(take));
                    left = left.subtract(take);
                    if (attribution != null) {
                        attribution.putIfAbsent(consumption.id(), line.getLineNumber());
                    }
                }
            }
        }
        Map<UUID, BigDecimal> reserved = new HashMap<>();
        Map<UUID, WorkOrderMaterial> byId = lines.stream()
                .collect(Collectors.toMap(WorkOrderMaterial::getId, Function.identity()));
        for (MaterialReservation reservation : open) {
            WorkOrderMaterial line = byId.get(reservation.getWorkOrderMaterialId());
            reserved.merge(line.getId(), convert(reservation.getOpenQuantity(), reservation.getUomId(), line.getUomId()),
                    BigDecimal::add);
        }
        Map<UUID, Coverage> coverage = new LinkedHashMap<>();
        lines.forEach(line -> coverage.put(line.getId(), new Coverage(line.getRequiredQuantity(),
                issued.getOrDefault(line.getId(), BigDecimal.ZERO), reserved.getOrDefault(line.getId(), BigDecimal.ZERO))));
        return coverage;
    }

    private MaterialsResponse view(WorkOrder order) {
        List<WorkOrderMaterial> lines = requirements.findByWorkOrderIdOrderByLineNumber(order.getId());
        List<ConsumptionRef> consumptions = consumptions(order);
        List<MaterialReservation> all = reservations.findByWorkOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        List<MaterialReservation> open = all.stream().filter(r -> r.getOpenQuantity().signum() > 0).toList();
        Map<UUID, Integer> attribution = new HashMap<>();
        Map<UUID, Coverage> coverage = coverage(order, lines, consumptions, open, attribution);

        Set<UUID> materialIds = lines.stream().map(WorkOrderMaterial::getMaterialId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> productIds = lines.stream().map(WorkOrderMaterial::getComponentProductId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, List<LotView>> freeLots = receipts.freeLots(materialIds, order.getFacilityId()).stream()
                .collect(Collectors.groupingBy(LotView::materialId));
        Map<UUID, List<ProductionBatchView>> freeBatches = production.availableBatches(productIds, order.getFacilityId())
                .stream().collect(Collectors.groupingBy(ProductionBatchView::productId));
        Map<UUID, MaterialReference> materialRefs = materials.references(materialIds);
        Map<UUID, ProductReference> productRefs = products.references(productIds);

        Map<UUID, String> units = unitCodes();
        Map<String, BigDecimal> availableLeft = new HashMap<>();
        List<MaterialLine> lineViews = new ArrayList<>();
        List<String> shortages = new ArrayList<>();
        for (WorkOrderMaterial line : lines) {
            boolean material = line.getMaterialId() != null;
            List<SourceOption> options = material
                    ? freeLots.getOrDefault(line.getMaterialId(), List.of()).stream()
                            .map(lot -> new SourceOption(LOT, lot.itemId(), lot.internalBatch(), lot.receivedOn(),
                                    lot.freeQuantity(), lot.uomId()))
                            .toList()
                    : freeBatches.getOrDefault(line.getComponentProductId(), List.of()).stream()
                            .map(batch -> new SourceOption(BATCH, batch.id(), batch.batchNumber(), null,
                                    batch.availableQuantity(), batch.uomId()))
                            .toList();
            // Lines naming the same component share what is free.
            BigDecimal available = availableLeft.computeIfAbsent(key(line), k -> options.stream()
                    .map(option -> convert(option.freeQuantity(), option.uomId(), line.getUomId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            Coverage c = coverage.get(line.getId());
            BigDecimal shortage = c.outstanding().subtract(available).max(BigDecimal.ZERO);
            availableLeft.put(key(line), available.subtract(c.outstanding()).max(BigDecimal.ZERO));
            String code = material ? materialRefs.get(line.getMaterialId()).code()
                    : productRefs.get(line.getComponentProductId()).code();
            String name = material ? materialRefs.get(line.getMaterialId()).name()
                    : productRefs.get(line.getComponentProductId()).name();
            if (shortage.signum() > 0) {
                shortages.add("%s is short by %s %s.".formatted(code, plain(shortage),
                        units.get(line.getUomId())));
            }
            lineViews.add(new MaterialLine(line.getLineNumber(), material ? MATERIAL : PRODUCT,
                    material ? line.getMaterialId() : line.getComponentProductId(), code, name, line.getUomId(),
                    line.getRequiredQuantity(), scaled(c.issued()), scaled(c.reserved()), scaled(c.outstanding()),
                    scaled(available), scaled(shortage), options));
        }

        Set<UUID> lotIds = Stream.concat(all.stream().map(MaterialReservation::getLotId),
                consumptions.stream().map(ConsumptionRef::lotId).filter(Objects::nonNull)).collect(Collectors.toSet());
        List<MaterialMovement> history = movements.findByWorkOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        history.stream().map(MaterialMovement::getLotId).filter(Objects::nonNull).forEach(lotIds::add);
        Set<UUID> batchIds = new HashSet<>();
        consumptions.stream().map(ConsumptionRef::parentBatchId).filter(Objects::nonNull).forEach(batchIds::add);
        history.stream().map(MaterialMovement::getSourceBatchId).filter(Objects::nonNull).forEach(batchIds::add);
        Sources sources = new Sources(
                lotIds.isEmpty() ? Map.of() : receipts.lots(lotIds).stream()
                        .collect(Collectors.toMap(LotView::itemId, Function.identity())),
                batchIds.isEmpty() ? Map.of() : production.views(batchIds).stream()
                        .collect(Collectors.toMap(ProductionBatchView::id, Function.identity())));
        Map<UUID, Integer> lineNumbers = lines.stream()
                .collect(Collectors.toMap(WorkOrderMaterial::getId, WorkOrderMaterial::getLineNumber));

        List<ReservationResponse> reservationViews = all.stream()
                .map(r -> new ReservationResponse(r.getId(), lineNumbers.get(r.getWorkOrderMaterialId()), r.getLotId(),
                        sources.lots().get(r.getLotId()).internalBatch(), r.getQuantity(), r.getOpenQuantity(),
                        r.getUomId(), r.getCreatedAt()))
                .toList();
        List<ConsumptionResponse> consumptionViews = consumptions.stream().map(consumption -> {
            boolean fromLot = consumption.lotId() != null;
            LotView lot = fromLot ? sources.lots().get(consumption.lotId()) : null;
            ProductionBatchView batch = fromLot ? null : sources.batches().get(consumption.parentBatchId());
            return new ConsumptionResponse(consumption.id(), attribution.get(consumption.id()), fromLot ? LOT : BATCH,
                    fromLot ? lot.itemId() : batch.id(), fromLot ? lot.internalBatch() : batch.batchNumber(),
                    fromLot ? lot.materialCode() : batch.productCode(), fromLot ? lot.materialName() : batch.productName(),
                    consumption.quantity(), fromLot ? lot.uomId() : batch.uomId());
        }).toList();
        Map<UUID, String> members = support.members();
        List<MovementResponse> movementViews = history.stream()
                .map(m -> new MovementResponse(m.getId(), m.getMovementType(),
                        m.getWorkOrderMaterialId() == null ? null : lineNumbers.get(m.getWorkOrderMaterialId()),
                        m.getLotId() != null ? sources.lots().get(m.getLotId()).internalBatch()
                                : sources.batches().get(m.getSourceBatchId()).batchNumber(),
                        m.getQuantity(), m.getUomId(), m.getReason(), m.getCreatedBy(), members.get(m.getCreatedBy()),
                        m.getCreatedAt()))
                .toList();
        return new MaterialsResponse(lineViews, reservationViews, consumptionViews, movementViews, shortages);
    }

    private LotView lotFor(WorkOrder order, WorkOrderMaterial line, UUID lotId) {
        LotView lot = receipts.lots(List.of(lotId)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Batch", lotId));
        if (!lot.materialId().equals(line.getMaterialId())) {
            throw new BusinessRuleException("Batch %s is not of line %d's material."
                    .formatted(lot.internalBatch(), line.getLineNumber()));
        }
        requireSameFacility(order, lot.facilityId());
        return lot;
    }

    private static void requireSameFacility(WorkOrder order, UUID facilityId) {
        if (!order.getFacilityId().equals(facilityId)) {
            throw new BusinessRuleException("Stock for %s comes from its own facility.".formatted(order.getWorkOrderNumber()));
        }
    }

    private WorkOrderMaterial line(WorkOrder order, int lineNumber) {
        return requirements.findByWorkOrderIdOrderByLineNumber(order.getId()).stream()
                .filter(line -> line.getLineNumber() == lineNumber)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("%s has no material line %d."
                        .formatted(order.getWorkOrderNumber(), lineNumber)));
    }

    private WorkOrder lock(UUID id) {
        WorkOrder order = workOrders.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        return order;
    }

    private static void requireStatus(WorkOrder order, Set<WorkOrder.Status> allowed, String action) {
        if (!allowed.contains(order.getStatus())) {
            throw new BusinessRuleException("Work order %s is %s, so you cannot %s.".formatted(
                    order.getWorkOrderNumber(), order.getStatus().name().toLowerCase().replace('_', ' '), action));
        }
    }

    private boolean repeated(WorkOrder order, String key) {
        if (key == null) {
            return false;
        }
        if (key.isBlank() || key.length() > MAX_KEY) {
            throw new BusinessRuleException("An Idempotency-Key is 1 to %d characters.".formatted(MAX_KEY));
        }
        return movements.existsByWorkOrderIdAndRequestKey(order.getId(), key);
    }

    private Map<UUID, String> unitCodes() {
        return materials.listUnits().stream().collect(Collectors.toMap(UnitResponse::id, UnitResponse::code));
    }

    private BigDecimal convert(BigDecimal quantity, UUID from, UUID to) {
        return scaled(from.equals(to) ? quantity : materials.convert(quantity, from, to));
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
