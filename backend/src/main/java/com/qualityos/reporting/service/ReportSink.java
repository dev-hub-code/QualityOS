package com.qualityos.reporting.service;

import com.qualityos.reporting.dto.ReportingDtos.ReportTable;
import java.util.List;

/** Takes a report as it is read: its heading first, with no rows, then each row. */
public interface ReportSink {

    void start(ReportTable heading);

    void row(List<String> values);
}
