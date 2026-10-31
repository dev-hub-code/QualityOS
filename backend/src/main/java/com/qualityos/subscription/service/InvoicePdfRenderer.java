package com.qualityos.subscription.service;

import com.qualityos.common.report.HtmlPdf;
import com.qualityos.subscription.entity.Subscription;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.samskivert.mustache.Template;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvoicePdfRenderer {

    private final HtmlPdf pdf;
    private final Template template;
    private final String sellerName;
    private final String sellerGstin;
    private final String sellerAddress;

    /** For writing a sample without a running application. */
    public static InvoicePdfRenderer forSample(HtmlPdf pdf) {
        return new InvoicePdfRenderer(pdf, "QualityOS", "", "");
    }

    InvoicePdfRenderer(
            HtmlPdf pdf,
            @Value("${qualityos.billing.seller-name}") String sellerName,
            @Value("${qualityos.billing.seller-gstin:}") String sellerGstin,
            @Value("${qualityos.billing.seller-address:}") String sellerAddress) {
        this.pdf = pdf;
        this.template = HtmlPdf.template("report-templates/invoice.html");
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
        model.put("tax", money(invoice.getTaxAmount()));
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

    /** A section in a template runs for an empty string, which would print a label with nothing after it. */
    private static List<String> present(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private static String money(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(value);
    }
}
