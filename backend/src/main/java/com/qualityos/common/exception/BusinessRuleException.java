package com.qualityos.common.exception;

/** A well-formed request that a business rule forbids, such as an invalid state transition. */
public class BusinessRuleException extends QualityOsException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
