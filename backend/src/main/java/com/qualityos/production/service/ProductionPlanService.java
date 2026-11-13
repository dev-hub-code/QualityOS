package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.WorkOrderDtos.NoteRequest;
import com.qualityos.production.dto.WorkOrderDtos.PlanDetail;
import com.qualityos.production.dto.WorkOrderDtos.PlanLineResponse;
import com.qualityos.production.dto.WorkOrderDtos.PlanResponse;
import com.qualityos.production.dto.WorkOrderDtos.PlanWorkOrder;
import com.qualityos.production.dto.WorkOrderDtos.ReasonRequest;
import com.qualityos.production.dto.WorkOrderDtos.SavePlanLineRequest;
import com.qualityos.production.dto.WorkOrderDtos.SavePlanRequest;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.entity.ProductionPlanLine;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.repository.ProductionPlanLineRepository;
import com.qualityos.production.repository.ProductionPlanRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production plans: what a facility means to make over a period. Lines change only while the plan is a draft; once
 * approved, work orders are raised from them, and each line shows how much is ordered against it.
 */
@Service
public class ProductionPlanService {

    private static final String ENTITY = "ProductionPlan";
    private static final Set<WorkOrder.Status> OPEN = EnumSet.of(WorkOrder.Status.DRAFT, WorkOrder.Status.SUBMITTED,
            WorkOrder.Status.APPROVED, WorkOrder.Status.RELEASED, WorkOrder.Status.IN_PROGRESS,
            WorkOrder.Status.ON_HOLD, WorkOrder.Status.PARTIALLY_COMPLETED);

    private final ProductionPlanRepository plans;
    private final ProductionPlanLineRepository lines;
    private final WorkOrderRepository workOrders;
    private final ProductService products;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final RevisionSupport support;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    ProductionPlanService(
            ProductionPlanRepository plans,
            ProductionPlanLineRepository lines,
            WorkOrderRepository workOrders,
            ProductService products,
            FacilityService facilities,
            FacilityScope scope,
            RevisionSupport support,
            DocumentNumberService numbers,
            AuditService audit) {
        this.plans = plans;
        this.lines = lines;
        this.workOrders = workOrders;
        this.products = products;
        this.facilities = facilities;
        this.scope = scope;
        this.support = support;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> list(
            String search, ProductionPlan.Status status, UUID facilityId, Pageable pageable) {
        Specification<ProductionPlan> filter = Specification.<ProductionPlan>where(
                        Searches.contains(search, "planNumber", "name"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("facilityId", facilityId));
        Page<ProductionPlan> page = plans.findAll(scope.limit(filter, "facilityId"), pageable);
        Map<UUID, String> codes = page.isEmpty() ? Map.of() : facilities.codes(
                page.getContent().stream().map(ProductionPlan::getFacilityId).collect(Collectors.toSet()));
        Map<UUID, String> members = support.members();
        return PageResponse.from(page, plan -> toResponse(plan, codes, members));
    }

    @Transactional(readOnly = true)
    public PlanDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public PlanDetail create(SavePlanRequest request) {
        ProductionPlan plan = new ProductionPlan(numbers.next("PP"), request.facilityId());
        facilities.requireActive(request.facilityId());
        scope.requireAllowed(request.facilityId(), "Facility", request.facilityId().toString());
        apply(plan, request);
        plans.saveAndFlush(plan);
        audit.record("PRODUCTION_PLAN_CREATED", ENTITY, plan.getId(), null, Map.of("number", plan.getPlanNumber()));
        return detail(plan);
    }

    /** The facility stays: the plan's work orders are made there. */
    @Transactional
    public PlanDetail update(UUID id, SavePlanRequest request) {
        ProductionPlan plan = load(id);
        plan.requireDraft();
        if (!plan.getFacilityId().equals(request.facilityId())) {
            throw new BusinessRuleException("A plan cannot move to another facility. Cancel it and plan there.");
        }
        apply(plan, request);
        lines.findByPlanIdOrderByDueDateAscIdAsc(id).forEach(line -> requireInPeriod(plan, line.getDueDate()));
        plans.saveAndFlush(plan);
        audit.record("PRODUCTION_PLAN_UPDATED", ENTITY, id);
        return detail(plan);
    }

    @Transactional
    public PlanDetail addLine(UUID id, SavePlanLineRequest request) {
        ProductionPlan plan = load(id);
        plan.requireDraft();
        ProductionPlanLine line = new ProductionPlanLine(id);
        ProductReference product = applyLine(plan, line, request);
        lines.saveAndFlush(line);
        audit.record("PRODUCTION_PLAN_LINE_ADDED", ENTITY, id, null,
                Map.of("product", product.code(), "quantity", request.quantity().toPlainString()));
        return detail(plan);
    }

    @Transactional
    public PlanDetail updateLine(UUID id, UUID lineId, SavePlanLineRequest request) {
        ProductionPlan plan = load(id);
        plan.requireDraft();
        ProductionPlanLine line = loadLine(id, lineId);
        ProductReference product = applyLine(plan, line, request);
        lines.saveAndFlush(line);
        audit.record("PRODUCTION_PLAN_LINE_CHANGED", ENTITY, id, null,
                Map.of("product", product.code(), "quantity", request.quantity().toPlainString()));
        return detail(plan);
    }

    @Transactional
    public PlanDetail removeLine(UUID id, UUID lineId) {
        ProductionPlan plan = load(id);
        plan.requireDraft();
        ProductionPlanLine line = loadLine(id, lineId);
        lines.delete(line);
        lines.flush();
        audit.record("PRODUCTION_PLAN_LINE_REMOVED", ENTITY, id, null, Map.of("line", lineId.toString()));
        return detail(plan);
    }

    @Transactional
    public PlanDetail approve(UUID id) {
        ProductionPlan plan = load(id);
        if (plan.getStatus() == ProductionPlan.Status.DRAFT && lines.findByPlanIdOrderByDueDateAscIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("Add at least one line before approving the plan.");
        }
        plan.approve(TenantContext.getCurrentUserId(), Instant.now());
        plans.saveAndFlush(plan);
        audit.record("PRODUCTION_PLAN_APPROVED", ENTITY, id, Map.of("status", "DRAFT"), Map.of("status", "APPROVED"));
        return detail(plan);
    }

    /** A plan is closed once its work orders are finished, so none may still be open. */
    @Transactional
    public PlanDetail close(UUID id, NoteRequest request) {
        ProductionPlan plan = load(id);
        List<UUID> lineIds = lineIds(id);
        if (!lineIds.isEmpty() && workOrders.existsByPlanLineIdInAndStatusIn(lineIds, OPEN)) {
            throw new BusinessRuleException("Some of this plan's work orders are still open. Finish or cancel them first.");
        }
        String reason = request == null || request.note() == null || request.note().isBlank() ? null : request.note().trim();
        plan.close(reason);
        plans.saveAndFlush(plan);
        audit.record("PRODUCTION_PLAN_CLOSED", ENTITY, id, Map.of("status", "APPROVED"), Map.of("status", "CLOSED"));
        return detail(plan);
    }

    /** A plan that has led to production is closed instead, so its history keeps what it achieved. */
    @Transactional
    public PlanDetail cancel(UUID id, ReasonRequest request) {
        ProductionPlan plan = load(id);
        List<UUID> lineIds = lineIds(id);
        if (!lineIds.isEmpty() && workOrders.existsByPlanLineIdInAndStatusIn(lineIds,
                EnumSet.complementOf(EnumSet.of(WorkOrder.Status.CANCELLED)))) {
            throw new BusinessRuleException("This plan has work orders. Cancel them first, or close the plan instead.");
        }
        String before = plan.getStatus().name();
        String reason = request.reason().trim();
        plan.cancel(reason);
        plans.saveAndFlush(plan);
        audit.record("PRODUCTION_PLAN_CANCELLED", ENTITY, id, Map.of("status", before),
                Map.of("status", "CANCELLED", "reason", reason));
        return detail(plan);
    }

    private List<UUID> lineIds(UUID planId) {
        return lines.findByPlanIdOrderByDueDateAscIdAsc(planId).stream().map(ProductionPlanLine::getId).toList();
    }

    private void apply(ProductionPlan plan, SavePlanRequest request) {
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new BusinessRuleException("The period cannot end before it starts.");
        }
        plan.setName(request.name().trim());
        plan.setPeriodStart(request.periodStart());
        plan.setPeriodEnd(request.periodEnd());
        plan.setNotes(request.notes());
    }

    private ProductReference applyLine(ProductionPlan plan, ProductionPlanLine line, SavePlanLineRequest request) {
        ProductReference product = products.requireActive(request.productId());
        requireInPeriod(plan, request.dueDate());
        line.setProductId(product.id());
        line.setQuantity(request.quantity());
        line.setDueDate(request.dueDate());
        line.setNotes(request.notes());
        return product;
    }

    private static void requireInPeriod(ProductionPlan plan, java.time.LocalDate due) {
        if (due.isBefore(plan.getPeriodStart()) || due.isAfter(plan.getPeriodEnd())) {
            throw new BusinessRuleException("Each line is due within the plan's period, %s to %s."
                    .formatted(plan.getPeriodStart(), plan.getPeriodEnd()));
        }
    }

    private ProductionPlan load(UUID id) {
        ProductionPlan plan = plans.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(plan.getFacilityId(), ENTITY, plan.getPlanNumber());
        return plan;
    }

    private ProductionPlanLine loadLine(UUID planId, UUID lineId) {
        return lines.findById(lineId)
                .filter(line -> line.getPlanId().equals(planId))
                .orElseThrow(() -> new ResourceNotFoundException("Plan line", lineId));
    }

    private PlanDetail detail(ProductionPlan plan) {
        List<ProductionPlanLine> content = lines.findByPlanIdOrderByDueDateAscIdAsc(plan.getId());
        Map<UUID, ProductReference> productRefs = products.references(
                content.stream().map(ProductionPlanLine::getProductId).collect(Collectors.toSet()));
        Map<UUID, List<WorkOrder>> ordersByLine = content.isEmpty() ? Map.of()
                : workOrders.findByPlanLineIdInOrderByWorkOrderNumber(
                                content.stream().map(ProductionPlanLine::getId).toList()).stream()
                        .collect(Collectors.groupingBy(WorkOrder::getPlanLineId));
        List<PlanLineResponse> lineResponses = content.stream().map(line -> {
            List<WorkOrder> orders = ordersByLine.getOrDefault(line.getId(), List.of());
            BigDecimal ordered = orders.stream()
                    .filter(order -> order.getStatus() != WorkOrder.Status.CANCELLED)
                    .map(WorkOrder::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal remaining = line.getQuantity().subtract(ordered)
                    .max(BigDecimal.ZERO.setScale(line.getQuantity().scale()));
            ProductReference product = productRefs.get(line.getProductId());
            return new PlanLineResponse(line.getId(), line.getProductId(), product.code(), product.name(),
                    product.uomId(), line.getQuantity(), line.getDueDate(), line.getNotes(), ordered, remaining,
                    ordered.compareTo(line.getQuantity()) > 0,
                    orders.stream().map(order -> new PlanWorkOrder(order.getId(), order.getWorkOrderNumber(),
                            order.getStatus(), order.getQuantity())).toList());
        }).toList();
        return new PlanDetail(toResponse(plan, facilities.codes(List.of(plan.getFacilityId())), support.members()),
                lineResponses);
    }

    private static PlanResponse toResponse(ProductionPlan plan, Map<UUID, String> codes, Map<UUID, String> members) {
        return new PlanResponse(plan.getId(), plan.getPlanNumber(), plan.getName(), plan.getFacilityId(),
                codes.get(plan.getFacilityId()), plan.getPeriodStart(), plan.getPeriodEnd(), plan.getStatus(),
                plan.getStatusReason(), plan.getNotes(), plan.getApprovedBy(), members.get(plan.getApprovedBy()),
                plan.getApprovedAt(), plan.getCreatedAt());
    }
}
