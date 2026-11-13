package com.qualityos.traceability.service;

import java.util.List;

/** Goods left under an override while these open recalls covered them. Their customer must be contacted too. */
public record DispatchedUnderRecall(List<String> recallNumbers) {
}
