package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.production.dto.ManufacturingDtos.ChangeMachineStatusRequest;
import com.qualityos.production.dto.ManufacturingDtos.MachineDetail;
import com.qualityos.production.dto.ManufacturingDtos.MachineResponse;
import com.qualityos.production.dto.ManufacturingDtos.SaveMachineRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveWorkCenterRequest;
import com.qualityos.production.dto.ManufacturingDtos.StatusEventResponse;
import com.qualityos.production.dto.ManufacturingDtos.WorkCenterDetail;
import com.qualityos.production.dto.ManufacturingDtos.WorkCenterResponse;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.MachineStatusEvent;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.repository.MachineRepository;
import com.qualityos.production.repository.MachineStatusEventRepository;
import com.qualityos.production.repository.WorkCenterRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Work centres sit in a facility, so a member limited to some facilities sees and changes only theirs. */
@Service
public class WorkCenterService {

    private static final String WORK_CENTER = "Work centre";
    private static final String MACHINE = "Machine";

    private final WorkCenterRepository workCenters;
    private final MachineRepository machines;
    private final MachineStatusEventRepository events;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final TenantAccessService access;
    private final AuditService audit;
    private final jakarta.persistence.EntityManager entityManager;

    WorkCenterService(
            WorkCenterRepository workCenters,
            MachineRepository machines,
            MachineStatusEventRepository events,
            FacilityService facilities,
            FacilityScope scope,
            TenantAccessService access,
            AuditService audit,
            jakarta.persistence.EntityManager entityManager) {
        this.workCenters = workCenters;
        this.machines = machines;
        this.events = events;
        this.facilities = facilities;
        this.scope = scope;
        this.access = access;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkCenterResponse> list(String search, UUID facilityId, Pageable pageable) {
        Specification<WorkCenter> filter = Specification.<WorkCenter>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name"))
                .and(Searches.equalTo("facilityId", facilityId));
        Page<WorkCenter> page = workCenters.findAll(scope.limit(filter, "facilityId"), pageable);
        Map<UUID, String> codes = facilityCodes(page.getContent());
        return PageResponse.from(page, centre -> toResponse(centre, codes));
    }

    @Transactional(readOnly = true)
    public WorkCenterDetail get(UUID id) {
        WorkCenter centre = load(id);
        List<Machine> list = machines.findByWorkCenterIdOrderByCode(id);
        return new WorkCenterDetail(toResponse(centre, facilityCodes(List.of(centre))),
                list.stream().map(machine -> toResponse(machine, centre)).toList());
    }

    @Transactional
    public WorkCenterResponse create(SaveWorkCenterRequest request) {
        String code = request.code().trim();
        if (workCenters.existsByCode(code)) {
            throw new ConflictException("Work centre %s already exists.".formatted(code));
        }
        WorkCenter centre = new WorkCenter(request.facilityId(), code);
        apply(centre, request);
        workCenters.saveAndFlush(centre);
        audit.record("WORK_CENTER_CREATED", "WorkCenter", centre.getId());
        return toResponse(centre, facilityCodes(List.of(centre)));
    }

    @Transactional
    public WorkCenterResponse update(UUID id, SaveWorkCenterRequest request) {
        WorkCenter centre = loadActive(id);
        String code = request.code().trim();
        if (workCenters.existsByCodeAndIdNot(code, id)) {
            throw new ConflictException("Work centre %s already exists.".formatted(code));
        }
        if (!centre.getFacilityId().equals(request.facilityId())) {
            throw new BusinessRuleException("A work centre cannot move to another facility. Create a new one there.");
        }
        centre.setCode(code);
        apply(centre, request);
        audit.record("WORK_CENTER_UPDATED", "WorkCenter", id);
        return toResponse(centre, facilityCodes(List.of(centre)));
    }

    /** A work centre with machines still in use stays, so that nothing points at a place that has gone. */
    @Transactional
    public void archive(UUID id) {
        WorkCenter centre = loadActive(id);
        if (machines.findByWorkCenterIdOrderByCode(id).stream()
                .anyMatch(machine -> machine.getStatus() != Machine.Status.INACTIVE)) {
            throw new BusinessRuleException("Make this work centre's machines inactive first.");
        }
        centre.setArchivedAt(Instant.now());
        audit.record("WORK_CENTER_ARCHIVED", "WorkCenter", id);
    }

    /** For a routing operation: the work centre must be in use and in one of this member's facilities. */
    @Transactional(readOnly = true)
    public WorkCenter requireUsable(UUID id) {
        return loadActive(id);
    }

    /** The machines at a work centre, for choosing one to run an operation on. */
    @Transactional(readOnly = true)
    public List<Machine> machinesAt(UUID workCenterId) {
        return machines.findByWorkCenterIdOrderByCode(workCenterId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Machine> machinesById(Collection<UUID> ids) {
        return machines.findAllById(ids).stream().collect(Collectors.toMap(Machine::getId, Function.identity()));
    }

    /**
     * The machine, locked and read again, so that two operations cannot both take it. Must be called inside the
     * caller's transaction.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Machine lockMachine(UUID id) {
        Machine machine = machines.findById(id).orElseThrow(() -> new ResourceNotFoundException(MACHINE, id));
        entityManager.refresh(machine, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return machine;
    }

    /**
     * Moves a machine because of what happens on the floor, keeping its history like a change made by hand. Does
     * nothing when it is already there.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void moveMachine(Machine machine, Machine.Status target, String reason) {
        Machine.Status previous = machine.getStatus();
        if (previous == target) {
            return;
        }
        machine.changeStatus(target, reason, Instant.now());
        machines.saveAndFlush(machine);
        events.save(new MachineStatusEvent(machine.getId(), previous, target, machine.getStatusReason()));
        audit.record("MACHINE_STATUS_CHANGED", MACHINE, machine.getId(), Map.of("status", previous.name()),
                Map.of("status", target.name(), "reason", Objects.toString(machine.getStatusReason(), "")));
    }

    /** Work centres by id, whatever their facility, for showing what a routing already names. */
    @Transactional(readOnly = true)
    public Map<UUID, WorkCenter> byId(Collection<UUID> ids) {
        return workCenters.findAllById(ids).stream().collect(Collectors.toMap(WorkCenter::getId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public PageResponse<MachineResponse> listMachines(
            String search, UUID workCenterId, Machine.Status status, Pageable pageable) {
        Specification<Machine> filter = Specification.<Machine>where(Searches.contains(search, "code", "name",
                        "serialNumber"))
                .and(Searches.equalTo("workCenterId", workCenterId))
                .and(Searches.equalTo("status", status));
        var restriction = scope.restriction();
        if (restriction.isPresent()) {
            Set<UUID> allowed = workCenters.findAll().stream()
                    .filter(centre -> restriction.get().contains(centre.getFacilityId()))
                    .map(WorkCenter::getId)
                    .collect(Collectors.toSet());
            filter = filter.and((root, query, cb) -> allowed.isEmpty()
                    ? cb.disjunction() : root.get("workCenterId").in(allowed));
        }
        Page<Machine> page = machines.findAll(filter, pageable);
        Map<UUID, WorkCenter> centres = byId(page.getContent().stream().map(Machine::getWorkCenterId).toList());
        return PageResponse.from(page, machine -> toResponse(machine, centres.get(machine.getWorkCenterId())));
    }

    @Transactional(readOnly = true)
    public MachineDetail getMachine(UUID id) {
        Machine machine = loadMachine(id);
        Map<UUID, String> names = access.activeMembers();
        List<StatusEventResponse> history = events.findTop100ByMachineIdOrderByCreatedAtDesc(id).stream()
                .map(event -> new StatusEventResponse(event.getFromStatus(), event.getToStatus(), event.getReason(),
                        event.getCreatedBy(), names.get(event.getCreatedBy()), event.getCreatedAt()))
                .toList();
        return new MachineDetail(toResponse(machine, workCenters.findById(machine.getWorkCenterId()).orElseThrow()),
                history);
    }

    @Transactional
    public MachineResponse createMachine(SaveMachineRequest request) {
        String code = request.code().trim();
        if (machines.existsByCode(code)) {
            throw new ConflictException("Machine %s already exists.".formatted(code));
        }
        WorkCenter centre = loadActive(request.workCenterId());
        Machine machine = new Machine(centre.getId(), code);
        applyMachine(machine, request);
        machines.saveAndFlush(machine);
        events.save(new MachineStatusEvent(machine.getId(), null, machine.getStatus(), null));
        audit.record("MACHINE_CREATED", MACHINE, machine.getId());
        return toResponse(machine, centre);
    }

    @Transactional
    public MachineResponse updateMachine(UUID id, SaveMachineRequest request) {
        Machine machine = loadMachine(id);
        String code = request.code().trim();
        if (machines.existsByCodeAndIdNot(code, id)) {
            throw new ConflictException("Machine %s already exists.".formatted(code));
        }
        WorkCenter centre = loadActive(request.workCenterId());
        machine.setCode(code);
        machine.setWorkCenterId(centre.getId());
        applyMachine(machine, request);
        audit.record("MACHINE_UPDATED", MACHINE, id);
        return toResponse(machine, centre);
    }

    /** Only the changes in {@link Machine#TRANSITIONS}; each one is kept in the machine's history. */
    @Transactional
    public MachineResponse changeStatus(UUID id, ChangeMachineStatusRequest request) {
        Machine machine = loadMachine(id);
        Machine.Status previous = machine.getStatus();
        machine.changeStatus(request.status(), request.reason(), Instant.now());
        machines.saveAndFlush(machine);
        events.save(new MachineStatusEvent(id, previous, machine.getStatus(), machine.getStatusReason()));
        audit.record("MACHINE_STATUS_CHANGED", MACHINE, id, Map.of("status", previous.name()),
                Map.of("status", machine.getStatus().name(), "reason", Objects.toString(machine.getStatusReason(), "")));
        return toResponse(machine, workCenters.findById(machine.getWorkCenterId()).orElseThrow());
    }

    private void apply(WorkCenter centre, SaveWorkCenterRequest request) {
        facilities.requireActive(request.facilityId());
        scope.requireAllowed(request.facilityId(), "Facility", request.facilityId().toString());
        if (request.productionLineId() != null) {
            facilities.requireActiveLine(request.facilityId(), request.productionLineId());
        }
        centre.setName(request.name().trim());
        centre.setProductionLineId(request.productionLineId());
        centre.setDescription(request.description());
    }

    private static void applyMachine(Machine machine, SaveMachineRequest request) {
        machine.setName(request.name().trim());
        machine.setManufacturer(request.manufacturer());
        machine.setModel(request.model());
        machine.setSerialNumber(request.serialNumber());
    }

    private WorkCenter load(UUID id) {
        WorkCenter centre = workCenters.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(WORK_CENTER, id));
        scope.requireAllowed(centre.getFacilityId(), WORK_CENTER, id.toString());
        return centre;
    }

    private WorkCenter loadActive(UUID id) {
        WorkCenter centre = load(id);
        if (centre.getArchivedAt() != null) {
            throw new BusinessRuleException("Work centre %s is archived.".formatted(centre.getCode()));
        }
        return centre;
    }

    private Machine loadMachine(UUID id) {
        Machine machine = machines.findById(id).orElseThrow(() -> new ResourceNotFoundException(MACHINE, id));
        WorkCenter centre = workCenters.findById(machine.getWorkCenterId()).orElseThrow();
        scope.requireAllowed(centre.getFacilityId(), MACHINE, id.toString());
        return machine;
    }

    private Map<UUID, String> facilityCodes(List<WorkCenter> centres) {
        Set<UUID> ids = new HashSet<>();
        centres.forEach(centre -> {
            ids.add(centre.getFacilityId());
            if (centre.getProductionLineId() != null) {
                ids.add(centre.getProductionLineId());
            }
        });
        return ids.isEmpty() ? Map.of() : facilities.codes(ids);
    }

    private static WorkCenterResponse toResponse(WorkCenter centre, Map<UUID, String> codes) {
        return new WorkCenterResponse(centre.getId(), centre.getCode(), centre.getName(), centre.getDescription(),
                centre.getFacilityId(), codes.get(centre.getFacilityId()), centre.getProductionLineId(),
                centre.getProductionLineId() == null ? null : codes.get(centre.getProductionLineId()),
                centre.getArchivedAt() != null);
    }

    private static MachineResponse toResponse(Machine machine, WorkCenter centre) {
        return new MachineResponse(machine.getId(), machine.getCode(), machine.getName(), machine.getManufacturer(),
                machine.getModel(), machine.getSerialNumber(), machine.getWorkCenterId(),
                centre == null ? null : centre.getCode(), centre == null ? null : centre.getFacilityId(),
                machine.getStatus(), machine.getStatusReason(), machine.getStatusChangedAt(),
                Stream.of(Machine.Status.values())
                        .filter(Machine.TRANSITIONS.get(machine.getStatus())::contains)
                        .toList());
    }
}
