package com.qualityos.subscription.service;

import com.qualityos.common.report.HtmlPdf;
import com.qualityos.subscription.entity.CreditNote;
import com.qualityos.subscription.entity.Refund;
import com.qualityos.subscription.entity.Subscription;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.samskivert.mustache.Template;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvoicePdfRenderer {

    private final HtmlPdf pdf;
    private final Template template;
    private final Template creditNoteTemplate;
    private final String sellerName;
    private final String sellerGstin;
    private final String sellerAddress;

    /** For writing a sample without a running application. */
    public static InvoicePdfRenderer forSample(HtmlPdf pdf) {
        return new InvoicePdfRenderer(pdf, "ProductionOS", "", "");
    }

    InvoicePdfRenderer(
            HtmlPdf pdf,
            @Value("${qualityos.billing.seller-name}") String sellerName,
            @Value("${qualityos.billing.seller-gstin:}") String sellerGstin,
            @Value("${qualityos.billing.seller-address:}") String sellerAddress) {
        this.pdf = pdf;
        this.template = HtmlPdf.template("report-templates/invoice.html");
        this.creditNoteTemplate = HtmlPdf.template("report-templates/credit-note.html");
        this.sellerName = sellerName;
        this.sellerGstin = sellerGstin;
        this.sellerAddress = sellerAddress;
    }

    public byte[] render(SubscriptionInvoice invoice) {
        Map<String, Object> model = new HashMap<>();
        model.put("invoice", invoice);
        model.put("cycle", invoice.getBillingCycle() == Subscription.Cycle.ANNUAL
                ? "annual" : "monthly");
        model.put("amount", money(invoice.getAmount()));
        model.put("listPrice", money(Objects.requireNonNullElse(invoice.getListPrice(), invoice.getAmount())));
        model.put("credit", invoice.getProrationCredit() == null ? List.of() : List.of(Map.of(
                "note", invoice.getProrationNote(), "amount", money(invoice.getProrationCredit()),
                "taxable", money(invoice.getAmount()),
                "extraDays", invoice.getExtraDays() > 0 ? List.of(invoice.getExtraDays()) : List.of())));
        model.put("tax", money(invoice.getTaxAmount()));
        model.put("cgst", amount(invoice.getCgstAmount()));
        model.put("sgst", amount(invoice.getSgstAmount()));
        model.put("igst", amount(invoice.getIgstAmount()));
        model.put("singleLine", invoice.getCgstAmount() == null && invoice.getIgstAmount() == null);
        model.put("halfPercent", invoice.getTaxPercent().divide(java.math.BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString());
        model.put("placeOfSupply", present(invoice.getPlaceOfSupply()));
        model.put("sacCode", present(invoice.getSacCode()));
        model.put("total", money(invoice.getTotal()));
        model.put("paid", invoice.getStatus() == SubscriptionInvoice.Status.PAID);
        model.put("void", invoice.getStatus() == SubscriptionInvoice.Status.VOID);
        model.put("sellerName", sellerName);
        model.put("sellerGstin", present(sellerGstin));
        model.put("sellerAddress", present(sellerAddress));
        model.put("buyerGstin", present(invoice.getBuyerGstin()));
        model.put("buyerAddress", present(invoice.getBuyerAddress()));
        model.put("footerName", HtmlPdf.cssString(sellerName));
        return pdf.render(template.execute(model));
    }

    /** A credit note against {@code invoice}, with the refund that went with it when there is one. */
    public byte[] render(CreditNote note, SubscriptionInvoice invoice, Refund refund) {
        Map<String, Object> model = new HashMap<>();
        model.put("note", note);
        model.put("invoice", invoice);
        model.put("amount", money(note.getAmount()));
        model.put("tax", money(note.getTaxAmount()));
        model.put("cgst", amount(note.getCgstAmount()));
        model.put("sgst", amount(note.getSgstAmount()));
        model.put("igst", amount(note.getIgstAmount()));
        model.put("singleLine", note.getCgstAmount() == null && note.getIgstAmount() == null);
        model.put("halfPercent", invoice.getTaxPercent().divide(BigDecimal.valueOf(2)).stripTrailingZeros().toPlainString());
        model.put("total", money(note.getTotal()));
        model.put("placeOfSupply", present(invoice.getPlaceOfSupply()));
        model.put("sacCode", present(invoice.getSacCode()));
        model.put("sellerName", sellerName);
        model.put("sellerGstin", present(sellerGstin));
        model.put("sellerAddress", present(sellerAddress));
        model.put("buyerGstin", present(invoice.getBuyerGstin()));
        model.put("buyerAddress", present(invoice.getBuyerAddress()));
        model.put("refund", refund == null ? List.of() : List.of(Map.of(
                "how", Refund.GATEWAY.equals(refund.getProvider()) ? "to the original payment method" : "by bank transfer",
                "state", switch (refund.getStatus()) {
                    case SUCCEEDED -> "made on " + refund.getRefundedOn();
                    case PENDING -> "in progress";
                    case FAILED -> "failed";
                },
                "reference", refund.getRefundReference())));
        model.put("footerName", HtmlPdf.cssString(sellerName));
        return pdf.render(creditNoteTemplate.execute(model));
    }

    /** A section in a template runs for an empty string, which would print a label with nothing after it. */
    private static List<String> present(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private static List<String> amount(BigDecimal value) {
        return value == null ? List.of() : List.of(money(value));
    }

    private static String money(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(value);
    }
}
