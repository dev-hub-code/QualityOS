"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { NO_PERIOD, PeriodFilter, periodQuery } from "@/components/analytics/period-filter";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { ReportTable } from "@/lib/types";

const REPORTS = [
  ["inspection", "Inspection report", "INSPECTION_VIEW"],
  ["supplier-quality", "Supplier quality report", "SUPPLIER_VIEW"],
  ["material-rejection", "Material rejection report", "INSPECTION_VIEW"],
  ["ncr", "NCR report", "NCR_VIEW"],
  ["capa", "CAPA report", "CAPA_VIEW"],
  ["calibration", "Calibration report", "CALIBRATION_VIEW"],
  ["audit", "Audit report", "AUDIT_VIEW"],
  ["batch-traceability", "Batch traceability report", "TRACEABILITY_VIEW"],
];
const PREVIEW_ROWS = 100;

export default function ReportsPage() {
  const { data: me } = useMe();
  const allowed = REPORTS.filter(([, , permission]) => me?.permissions.includes(permission));
  const [chosen, setChosen] = useState("");
  const [period, setPeriod] = useState(NO_PERIOD);
  const slug = chosen || allowed[0]?.[0];
  const query = periodQuery(period);
  const path = `/reports/${slug}?${query}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<ReportTable>(path),
    enabled: Boolean(slug),
    placeholderData: keepPreviousData,
    retry: false,
  });
  return (
    <div className="space-y-6">
      <PageHeader title="Reports" />
      <div className="space-y-1.5">
        <Label htmlFor="report-type">Report</Label>
        <select
          id="report-type"
          className="h-9 max-w-full rounded-md border bg-background px-2 text-sm"
          value={slug ?? ""}
          onChange={(event) => setChosen(event.target.value)}
        >
          {allowed.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <PeriodFilter value={period} onChange={setPeriod} />
      {error && <QueryState error={error} />}
      {!data && !error && <QueryState error={null} />}
      {data && (
        <>
          <div className="flex flex-wrap items-center gap-4 text-sm">
            <span className="text-muted-foreground">
              {data.rows.length} rows. {data.periodLabel}.
            </span>
            <a className="underline" href={`/api/v1/reports/${slug}.pdf?${query}`}>
              Download PDF
            </a>
            <a className="underline" href={`/api/v1/reports/${slug}.xlsx?${query}`}>
              Download Excel
            </a>
          </div>
          <p className="text-sm text-muted-foreground">{data.note}</p>
          {data.truncated && (
            <p className="text-sm text-destructive">This report stops at {data.rows.length} rows. Choose fewer dates.</p>
          )}
          {data.rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing to report for this period.</p>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    {data.columns.map((column) => (
                      <TableHead key={column.label} className={column.numeric ? "text-right" : ""}>
                        {column.label}
                      </TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.rows.slice(0, PREVIEW_ROWS).map((row, index) => (
                    <TableRow key={index}>
                      {row.map((value, column) => (
                        <TableCell key={column} className={data.columns[column].numeric ? "text-right" : ""}>
                          {value?.replaceAll("_", " ")}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          {data.rows.length > PREVIEW_ROWS && (
            <p className="text-sm text-muted-foreground">Showing the first {PREVIEW_ROWS}. The downloads hold every row.</p>
          )}
        </>
      )}
    </div>
  );
}
