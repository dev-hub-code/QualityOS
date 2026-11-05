package com.qualityos.calibration.service;

import java.util.UUID;

/**
 * A failed calibration's review decided that an inspection's goods must be inspected again. Published in the
 * decision's transaction. The inspection module holds the goods, since calibration cannot depend on it.
 */
public record ReinspectionRequired(UUID inspectionId, String instrumentCode) {}
