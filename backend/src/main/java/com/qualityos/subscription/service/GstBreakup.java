package com.qualityos.subscription.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Splits an invoice's GST by comparing the state codes that open the seller's and buyer's GSTINs. Without both, the
 * treatment stays undetermined and the invoice shows one GST line, because place of supply would be a guess. An
 * accountant must review this before it is relied on: it does not cover exemptions, SEZ supplies or exports.
 *
 * @param placeOfSupply the buyer's two-digit state code, when known
 */
public record GstBreakup(String treatment, String placeOfSupply, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {

    private static final int STATE_CODE_LENGTH = 2;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    public static GstBreakup of(String sellerGstin, String buyerGstin, BigDecimal tax) {
        String seller = stateCode(sellerGstin);
        String buyer = stateCode(buyerGstin);
        if (seller == null || buyer == null) {
            return new GstBreakup("UNDETERMINED", buyer, null, null, null);
        }
        if (!seller.equals(buyer)) {
            return new GstBreakup("INTER_STATE", buyer, null, null, tax);
        }
        BigDecimal half = tax.divide(TWO, 2, RoundingMode.HALF_UP);
        return new GstBreakup("INTRA_STATE", buyer, half, tax.subtract(half), null);
    }

    private static String stateCode(String gstin) {
        if (gstin == null || gstin.length() < STATE_CODE_LENGTH) {
            return null;
        }
        String code = gstin.substring(0, STATE_CODE_LENGTH);
        return code.chars().allMatch(Character::isDigit) ? code : null;
    }
}
