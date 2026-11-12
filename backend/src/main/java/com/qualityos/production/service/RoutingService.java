package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ManufacturingDtos.ApproveRequest;
import com.qualityos.production.dto.ManufacturingDtos.CreateRevisionRequest;
import com.qualityos.production.dto.ManufacturingDtos.OperationResponse;
import com.qualityos.production.dto.ManufacturingDtos.ParameterResponse;
import com.qualityos.production.dto.ManufacturingDtos.RetireRequest;
import com.qualityos.production.dto.ManufacturingDtos.RevisionResponse;
import com.qualityos.production.dto.ManufacturingDtos.RoutingDetail;
import com.qualityos.production.dto.ManufacturingDtos.SaveOperationRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveParameterRequest;
import com.qualityos.production.dto.ManufacturingDtos.SendBackRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateRevisionRequest;
import com.qualityos.production.entity.ProcessParameter;
import com.qualityos.production.entity.RevisionedDefinition.Status;
import com.qualityos.production.entity.Routing;
import com.qualityos.production.entity.RoutingOperation;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.repository.ProcessParameterRepository;
import com.qualityos.production.repository.RoutingOperationRepository;
import com.qualityos.production.repository.RoutingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.qualityos.common.persistence.Searches;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Routings: the operations, in sequence, that make a product, each at a work centre. */
@Service
public class RoutingService {

    private static final String ENTITY = "Routing";
    private static final List<Status> OPEN = List.of(Status.DRAFT, Status.SUBMITTED);

    private final RoutingRepository routings;
    private final RoutingOperationRepository operations;
    private final ProcessParameterRepository parameters;
    private final WorkCenterService workCenters;
    private final ProductService products;
    private final RevisionSupport support;
    private final AuditService audit;

    RoutingService(
            RoutingRepository routings,
            RoutingOperationRepository operations,
            ProcessParameterRepository parameters,
            WorkCenterService workCenters,
            ProductService products,
            RevisionSupport support,
            AuditService audit) {
        this.routings = routings;
        this.operations = operations;
        this.parameters = parameters;
        this.workCenters = workCenters;
        this.products = products;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<RevisionResponse> list(String search, UUID productId, Status status, Pageable pageable) {
        Specification<Routing> filter = Specification.<Routing>where(Searches.equalTo("productId", productId))
                .and(Searches.equalTo("status", status));
        if (search != null && !search.isBlank()) {
            List<UUID> matching = products.idsMatching(search);
            filter = filter.and((root, query, cb) -> matching.isEmpty()
                    ? cb.disjunction() : root.get("productId").in(matching));
        }
        Page<Routing> page = routings.findAll(filter, pageable);
        Map<UUID, ProductReference> refs = products.references(
                page.getContent().stream().map(Routing::getProductId).toList());
        Map<UUID, String> members = support.members();
        LocalDate today = support.today();
        return PageResponse.from(page, routing -> RevisionSupport.toResponse(
                routing, ENTITY.toUpperCase(), refs.get(routing.getProductId()), null, members, today));
    }

    @Transactional(readOnly = true)
    public RoutingDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public RoutingDetail create(CreateRevisionRequest request) {
        ProductReference product = products.requireActive(request.productId());
        if (routings.existsByProductIdAndStatusIn(product.id(), OPEN)) {
            throw new ConflictException("%s already has a routing revision in progress. Finish or discard it first."
                    .formatted(product.code()));
        }
        Routing source = null;
        if (request.copyFromId() != null) {
            source = load(request.copyFromId());
            if (!source.getProductId().equals(product.id())) {
                throw new BusinessRuleException("Copy a revision of the same product.");
            }
        }
        Routing routing = new Routing(product.id(), routings.lastRevision(product.id()) + 1, request.changeNote());
        routings.saveAndFlush(routing);
        if (source != null) {
            copyOperations(source, routing);
        }
        audit.record("ROUTING_DRAFTED", ENTITY, routing.getId(), null,
                Map.of("product", product.code(), "revision", routing.getRevision()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail update(UUID id, UpdateRevisionRequest request) {
        Routing routing = load(id);
        routing.requireDraft();
        routing.setChangeNote(request.changeNote());
        audit.record("ROUTING_UPDATED", ENTITY, id);
        return detail(routing);
    }

    @Transactional
    public RoutingDetail addOperation(UUID id, SaveOperationRequest request) {
        Routing routing = load(id);
        routing.requireDraft();
        if (operations.existsByRoutingIdAndSequence(id, request.sequence())) {
            throw new ConflictException("Step %d is already used.".formatted(request.sequence()));
        }
        RoutingOperation operation = new RoutingOperation(id);
        apply(operation, request);
        operations.saveAndFlush(operation);
        audit.record("ROUTING_OPERATION_ADDED", ENTITY, id, null, Map.of("sequence", request.sequence()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail updateOperation(UUID id, UUID operationId, SaveOperationRequest request) {
        Routing routing = load(id);
        routing.requireDraft();
        RoutingOperation operation = loadOperation(id, operationId);
        if (operation.getSequence() != request.sequence()
                && operations.existsByRoutingIdAndSequence(id, request.sequence())) {
            throw new ConflictException("Step %d is already used.".formatted(request.sequence()));
        }
        apply(operation, request);
        audit.record("ROUTING_OPERATION_CHANGED", ENTITY, id, null, Map.of("sequence", request.sequence()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail removeOperation(UUID id, UUID operationId) {
        Routing routing = load(id);
        routing.requireDraft();
        RoutingOperation operation = loadOperation(id, operationId);
        parameters.deleteAll(parameters.findByOperationIdInOrderByCode(List.of(operationId)));
        parameters.flush();
        operations.delete(operation);
        operations.flush();
        audit.record("ROUTING_OPERATION_REMOVED", ENTITY, id, null, Map.of("sequence", operation.getSequence()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail addParameter(UUID id, UUID operationId, SaveParameterRequest request) {
        Routing routing = load(id);
        routing.requireDraft();
        loadOperation(id, operationId);
        String code = request.code().trim();
        if (parameters.existsByOperationIdAndCode(operationId, code)) {
            throw new ConflictException("This operation already has a parameter %s.".formatted(code));
        }
        ProcessParameter parameter = ProcessParameter.ofOperation(operationId);
        Parameters.apply(parameter, request);
        parameters.saveAndFlush(parameter);
        audit.record("ROUTING_PARAMETER_ADDED", ENTITY, id, null, Map.of("parameter", code));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail removeParameter(UUID id, UUID operationId, UUID parameterId) {
        Routing routing = load(id);
        routing.requireDraft();
        loadOperation(id, operationId);
        ProcessParameter parameter = parameters.findById(parameterId)
                .filter(found -> operationId.equals(found.getOperationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Parameter", parameterId));
        parameters.delete(parameter);
        parameters.flush();
        audit.record("ROUTING_PARAMETER_REMOVED", ENTITY, id, null, Map.of("parameter", parameter.getCode()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail submit(UUID id) {
        Routing routing = load(id);
        List<RoutingOperation> steps = operations.findByRoutingIdOrderBySequence(id);
        if (steps.isEmpty()) {
            throw new BusinessRuleException("Add at least one operation before submitting.");
        }
        requireWorkCentresInUse(steps);
        support.submit(routing);
        audit.record("ROUTING_SUBMITTED", ENTITY, id);
        return detail(routing);
    }

    @Transactional
    public RoutingDetail sendBack(UUID id, SendBackRequest request) {
        Routing routing = load(id);
        routing.sendBack(request.note().trim());
        audit.record("ROUTING_SENT_BACK", ENTITY, id, null, Map.of("note", request.note().trim()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail approve(UUID id, ApproveRequest request) {
        Routing routing = load(id);
        requireWorkCentresInUse(operations.findByRoutingIdOrderBySequence(id));
        RoutingDetail before = detail(routing);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", "ROUTING");
        snapshot.put("productId", routing.getProductId());
        snapshot.put("productCode", before.revision().productCode());
        snapshot.put("productName", before.revision().productName());
        snapshot.put("revision", routing.getRevision());
        snapshot.put("operations", before.operations());
        support.approve(routing, routings.findByProductIdAndStatus(routing.getProductId(), Status.APPROVED), request,
                ENTITY, "ROUTING_APPROVE", snapshot);
        routings.flush();
        audit.record("ROUTING_APPROVED", ENTITY, id, null,
                Map.of("revision", routing.getRevision(), "effectiveFrom", routing.getEffectiveFrom().toString()));
        return detail(routing);
    }

    @Transactional
    public RoutingDetail retire(UUID id, RetireRequest request) {
        Routing routing = load(id);
        routing.retire(Instant.now());
        audit.record("ROUTING_RETIRED", ENTITY, id, null, Map.of("reason", request.reason().trim()));
        return detail(routing);
    }

    @Transactional
    public void discard(UUID id) {
        Routing routing = load(id);
        routing.requireDraft();
        if (routing.getSubmittedAt() != null) {
            throw new BusinessRuleException("This revision was submitted once, so it is kept. Edit it instead.");
        }
        List<RoutingOperation> steps = operations.findByRoutingIdOrderBySequence(id);
        parameters.deleteAll(parameters.findByOperationIdInOrderByCode(
                steps.stream().map(RoutingOperation::getId).toList()));
        parameters.flush();
        operations.deleteAll(steps);
        operations.flush();
        routings.delete(routing);
        routings.flush();
        audit.record("ROUTING_DISCARDED", ENTITY, id);
    }

    private void requireWorkCentresInUse(List<RoutingOperation> steps) {
        Map<UUID, WorkCenter> centres = workCenters.byId(steps.stream().map(RoutingOperation::getWorkCenterId).toList());
        steps.stream()
                .map(step -> centres.get(step.getWorkCenterId()))
                .filter(centre -> centre.getArchivedAt() != null)
                .findFirst()
                .ifPresent(centre -> {
                    throw new BusinessRuleException("Work centre %s is archived. Choose another for its operations."
                            .formatted(centre.getCode()));
                });
    }

    private void apply(RoutingOperation operation, SaveOperationRequest request) {
        WorkCenter centre = workCenters.requireUsable(request.workCenterId());
        operation.setSequence(request.sequence());
        operation.setName(request.name().trim());
        operation.setWorkCenterId(centre.getId());
        operation.setSetupMinutes(request.setupMinutes() == null ? BigDecimal.ZERO : request.setupMinutes());
        operation.setRunMinutes(request.runMinutes() == null ? BigDecimal.ZERO : request.runMinutes());
        operation.setInstructions(request.instructions());
    }

    private void copyOperations(Routing source, Routing target) {
        List<RoutingOperation> steps = operations.findByRoutingIdOrderBySequence(source.getId());
        Map<UUID, List<ProcessParameter>> byOperation = parameters.findByOperationIdInOrderByCode(
                        steps.stream().map(RoutingOperation::getId).toList()).stream()
                .collect(Collectors.groupingBy(ProcessParameter::getOperationId));
        for (RoutingOperation step : steps) {
            RoutingOperation copy = new RoutingOperation(target.getId());
            copy.setSequence(step.getSequence());
            copy.setName(step.getName());
            copy.setWorkCenterId(step.getWorkCenterId());
            copy.setSetupMinutes(step.getSetupMinutes());
            copy.setRunMinutes(step.getRunMinutes());
            copy.setInstructions(step.getInstructions());
            operations.saveAndFlush(copy);
            for (ProcessParameter parameter : byOperation.getOrDefault(step.getId(), List.of())) {
                parameters.save(Parameters.copy(parameter, ProcessParameter.ofOperation(copy.getId())));
            }
        }
        parameters.flush();
    }

    private RoutingDetail detail(Routing routing) {
        List<RoutingOperation> steps = operations.findByRoutingIdOrderBySequence(routing.getId());
        Map<UUID, WorkCenter> centres = workCenters.byId(steps.stream().map(RoutingOperation::getWorkCenterId).toList());
        Map<UUID, List<ParameterResponse>> byOperation = parameters.findByOperationIdInOrderByCode(
                        steps.stream().map(RoutingOperation::getId).toList()).stream()
                .collect(Collectors.groupingBy(ProcessParameter::getOperationId,
                        Collectors.mapping(Parameters::toResponse, Collectors.toList())));
        List<OperationResponse> list = steps.stream().map(step -> {
            WorkCenter centre = centres.get(step.getWorkCenterId());
            return new OperationResponse(step.getId(), step.getSequence(), step.getName(), step.getWorkCenterId(),
                    centre.getCode(), centre.getName(), step.getSetupMinutes(), step.getRunMinutes(),
                    step.getInstructions(), byOperation.getOrDefault(step.getId(), List.of()));
        }).toList();
        ProductReference product = products.references(List.of(routing.getProductId())).get(routing.getProductId());
        return new RoutingDetail(RevisionSupport.toResponse(routing, "ROUTING", product, null, support.members(),
                support.today()), list);
    }

    private Routing load(UUID id) {
        return routings.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private RoutingOperation loadOperation(UUID routingId, UUID operationId) {
        return operations.findById(operationId)
                .filter(operation -> operation.getRoutingId().equals(routingId))
                .orElseThrow(() -> new ResourceNotFoundException("Operation", operationId));
    }
}
