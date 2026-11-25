package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.report.HtmlPdf;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.production.dto.CompletionDtos.OperationRecord;
import com.qualityos.production.dto.CompletionDtos.ProductionRecord;
import com.qualityos.production.dto.CompletionDtos.ReceiptResponse;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderResponse;
import com.qualityos.traceability.service.ProductionService;
import com.samskivert.mustache.Template;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The production record of the batch a work order made, as a PDF for a customer, an auditor or the batch file. */
@Component
public class ProductionRecordPdf {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final WorkOrderService workOrders;
    private final CompletionService completion;
    private final OrganizationService organizations;
    private final ProductionService production;
    private final HtmlPdf pdf;
    private final Template template;

    ProductionRecordPdf(WorkOrderService workOrders, CompletionService completion, OrganizationService organizations,
            HtmlPdf pdf, ProductionService production) {
        this.production = production;
        this.workOrders = workOrders;
        this.completion = completion;
        this.organizations = organizations;
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/production-record.html");
    }

    public record Document(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public Document render(UUID workOrderId) {
        WorkOrderResponse order = workOrders.get(workOrderId).workOrder();
        if (order.productionBatchId() == null) {
            throw new BusinessRuleException("This work order has not made a batch yet, so it has no production record.");
        }
        ProductionRecord record = completion.record(order.productionBatchId()).orElseThrow();
        OrganizationResponse organization = organizations.getCurrent();
        ZoneId zone = ZoneId.of(organization.timezone());
        DateTimeFormatter when = WHEN.withZone(zone);
        List<Map<String, Object>> operations = record.operations().stream().map(step -> operation(step, when))
                .toList();
        Map<String, Object> model = new HashMap<>();
        model.put("order", order);
        model.put("record", record);
        model.put("quantity", plain(order.quantity()));
        model.put("organizationName", organization.name());
        model.put("footerName", HtmlPdf.cssString(organization.name()));
        model.put("generated", DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(zone)
                .format(Instant.now()));
        model.put("status", label(record.status().name()));
        model.put("operations", operations);
        model.put("hasOperations", !operations.isEmpty());
        model.put("gates", record.gates());
        model.put("hasGates", !record.gates().isEmpty());
        model.put("reworks", record.reworkOrders());
        model.put("hasReworks", !record.reworkOrders().isEmpty());
        ReceiptResponse receipt = record.receipt();
        if (receipt != null) {
            long serials = production.serialCount(order.productionBatchId());
            model.put("receipt", Map.of(
                    "quantity", plain(receipt.quantity()),
                    "expected", receipt.expectedQuantity() == null ? "" : plain(receipt.expectedQuantity()),
                    "rejected", plain(receipt.rejectedQuantity()),
                    "scrap", plain(receipt.scrapQuantity()),
                    "reworked", plain(receipt.reworkedQuantity()),
                    "by", receipt.receivedByName() == null ? "" : receipt.receivedByName(),
                    "at", when.format(receipt.receivedAt()),
                    "shortReason", receipt.shortReason() == null ? "" : receipt.shortReason(),
                    "varianceReason", receipt.varianceReason() == null ? "" : receipt.varianceReason(),
                    // Counted now: serial numbers are often registered on the batch after it is received.
                    "serials", serials == 0 ? List.of() : List.of(serials)));
        }
        return new Document(order.workOrderNumber() + "-production-record.pdf", pdf.render(template.execute(model)));
    }

    private static Map<String, Object> operation(OperationRecord step, DateTimeFormatter when) {
        return Map.ofEntries(
                Map.entry("sequence", step.sequence()),
                Map.entry("name", step.name()),
                Map.entry("centre", step.workCenterCode() == null ? "" : step.workCenterCode()),
                Map.entry("status", label(step.status())),
                Map.entry("operators", String.join(", ", step.operators())),
                Map.entry("machines", String.join(", ", step.machines())),
                Map.entry("started", step.startedAt() == null ? "" : when.format(step.startedAt())),
                Map.entry("completed", step.completedAt() == null ? "" : when.format(step.completedAt())),
                Map.entry("good", plain(step.goodQuantity())),
                Map.entry("rejected", plain(step.rejectedQuantity())),
                Map.entry("scrap", plain(step.scrapQuantity())));
    }

    private static String label(String status) {
        String words = status.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
