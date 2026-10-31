package com.qualityos.auditmanagement.service;

import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistDetail;
import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistItemRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistItemResponse;
import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistSummary;
import com.qualityos.auditmanagement.dto.AuditDtos.SaveChecklistRequest;
import com.qualityos.auditmanagement.entity.AuditChecklist;
import com.qualityos.auditmanagement.entity.AuditChecklistItem;
import com.qualityos.auditmanagement.repository.AuditChecklistItemRepository;
import com.qualityos.auditmanagement.repository.AuditChecklistRepository;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Changing a checklist affects audits planned afterwards, because each audit keeps a copy of its questions. */
@Service
public class AuditChecklistService {

    private static final String ENTITY = "AuditChecklist";
    private static final String RESOURCE = "Audit checklist";

    private final AuditChecklistRepository checklists;
    private final AuditChecklistItemRepository items;
    private final AuditService audit;

    AuditChecklistService(
            AuditChecklistRepository checklists, AuditChecklistItemRepository items, AuditService audit) {
        this.checklists = checklists;
        this.items = items;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ChecklistSummary> list(String search, Pageable pageable) {
        Specification<AuditChecklist> filter =
                Specification.<AuditChecklist>where(Searches.notArchived()).and(Searches.contains(search, "code", "name"));
        return PageResponse.from(checklists.findAll(filter, pageable),
                checklist -> new ChecklistSummary(checklist.getId(), checklist.getCode(), checklist.getName()));
    }

    @Transactional(readOnly = true)
    public ChecklistDetail get(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public ChecklistDetail create(SaveChecklistRequest request) {
        String code = request.code().trim();
        if (checklists.existsByCode(code)) {
            throw new ConflictException("Checklist code %s is already in use.".formatted(code));
        }
        AuditChecklist checklist = checklists.saveAndFlush(new AuditChecklist(code, request.name().trim()));
        saveItems(checklist.getId(), request.items());
        audit.record("AUDIT_CHECKLIST_CREATED", ENTITY, checklist.getId());
        return toDetail(checklist);
    }

    @Transactional
    public ChecklistDetail update(UUID id, SaveChecklistRequest request) {
        AuditChecklist checklist = load(id);
        checklist.setName(request.name().trim());
        items.deleteByChecklistId(id);
        items.flush();
        saveItems(id, request.items());
        audit.record("AUDIT_CHECKLIST_UPDATED", ENTITY, id);
        return toDetail(checklist);
    }

    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("AUDIT_CHECKLIST_ARCHIVED", ENTITY, id);
    }

    /** @return the questions as they stand, for an audit to copy */
    List<AuditChecklistItem> questions(UUID id) {
        load(id);
        return items.findByChecklistIdOrderByPosition(id);
    }

    String name(UUID id) {
        return checklists.findById(id).map(AuditChecklist::getName).orElse(null);
    }

    private void saveItems(UUID checklistId, List<ChecklistItemRequest> requests) {
        for (int index = 0; index < requests.size(); index++) {
            ChecklistItemRequest item = requests.get(index);
            items.save(new AuditChecklistItem(checklistId, index + 1, item.question().trim(), item.clauseReference()));
        }
        items.flush();
    }

    private AuditChecklist load(UUID id) {
        return checklists.findById(id)
                .filter(checklist -> checklist.getArchivedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id.toString()));
    }

    private ChecklistDetail toDetail(AuditChecklist checklist) {
        return new ChecklistDetail(checklist.getId(), checklist.getCode(), checklist.getName(),
                items.findByChecklistIdOrderByPosition(checklist.getId()).stream()
                        .map(item -> new ChecklistItemResponse(
                                item.getPosition(), item.getQuestion(), item.getClauseReference()))
                        .toList());
    }
}
