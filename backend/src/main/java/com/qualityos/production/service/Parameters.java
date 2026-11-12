package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.production.dto.ManufacturingDtos.ParameterResponse;
import com.qualityos.production.dto.ManufacturingDtos.SaveParameterRequest;
import com.qualityos.production.entity.ProcessParameter;
import java.math.BigDecimal;

/** Process parameters, shared by recipes and routing operations. */
final class Parameters {

    private Parameters() {
    }

    static void apply(ProcessParameter parameter, SaveParameterRequest request) {
        BigDecimal target = request.target();
        BigDecimal min = request.minimum();
        BigDecimal max = request.maximum();
        if (target == null && min == null && max == null) {
            throw new BusinessRuleException("Give a target, a lower limit or an upper limit.");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new BusinessRuleException("The lower limit is above the upper limit.");
        }
        if (target != null && ((min != null && target.compareTo(min) < 0) || (max != null && target.compareTo(max) > 0))) {
            throw new BusinessRuleException("The target is outside the limits.");
        }
        parameter.setCode(request.code().trim());
        parameter.setName(request.name().trim());
        parameter.setUnit(request.unit());
        parameter.setTarget(target);
        parameter.setMinimum(min);
        parameter.setMaximum(max);
    }

    static ProcessParameter copy(ProcessParameter from, ProcessParameter to) {
        to.setCode(from.getCode());
        to.setName(from.getName());
        to.setUnit(from.getUnit());
        to.setTarget(from.getTarget());
        to.setMinimum(from.getMinimum());
        to.setMaximum(from.getMaximum());
        return to;
    }

    static ParameterResponse toResponse(ProcessParameter parameter) {
        return new ParameterResponse(parameter.getId(), parameter.getCode(), parameter.getName(), parameter.getUnit(),
                parameter.getTarget(), parameter.getMinimum(), parameter.getMaximum());
    }
}
