package com.qualityos.organization.service;

/**
 * The least severe NCR a rule covers. {@code MAJOR} covers major and critical NCRs, {@code MINOR} covers every NCR,
 * and {@code NEVER} covers none. The quality module decides what each means for its severities.
 */
public enum SeverityThreshold { CRITICAL, MAJOR, MINOR, NEVER }
