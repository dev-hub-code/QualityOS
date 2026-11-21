"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { ControlChart, type ChartPoint } from "@/components/spc/control-chart";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import {
  capabilityVerdict,
  formatIndex,
  formatMeasure,
  type ChartType,
  type Characteristic,
  type SpcChart,
} from "@/lib/spc";
import type { Facility } from "@/lib/types";

const SELECT_CLASS = "h-9 rounded-xl border border-input bg-white/70 px-2 text-sm";
const PERIODS = [
  { days: 90, label: "Last 90 days" },
  { days: 180, label: "Last 6 months" },
  { days: 365, label: "Last 12 months" },
] as const;
const CHARTS: { value: ChartType | null; label: string }[] = [
  { value: null, label: "Suggested" },
  { value: "XBAR_R", label: "X̄-R" },
  { value: "I_MR", label: "Individuals" },
];
const STATUS_COLOUR = { good: "var(--viz-good)", warning: "var(--viz-warning)", critical: "var(--viz-critical)" };

const keyOf = (characteristic: Characteristic) => `${characteristic.templateId}/${characteristic.parameterCode}`;

function isoDay(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function Figure({ label, value, children }: { label: string; value: string; children?: React.ReactNode }) {
  return (
    <div className="rounded-xl border px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
      {children}
    </div>
  );
}

export default function SpcPage() {
  const canSeeFacilities = usePermission("FACILITY_VIEW");
  const [chosen, setChosen] = useState<string | null>(null);
  const [chartType, setChartType] = useState<ChartType | null>(null);
  const [days, setDays] = useState<number>(PERIODS[0].days);
  const [facilityId, setFacilityId] = useState("");

  const characteristics = useQuery({
    queryKey: ["/spc/characteristics"],
    queryFn: () => api.get<Characteristic[]>("/spc/characteristics"),
  });
  const facilities = useQuery({
    queryKey: ["/facilities", "spc"],
    queryFn: () => api.get<PageResponse<Facility>>("/facilities?size=100"),
    enabled: canSeeFacilities,
  });
  const list = characteristics.data ?? [];
  const selected = list.find((characteristic) => keyOf(characteristic) === chosen) ?? list[0];

  const chart = useQuery({
    queryKey: ["/spc/chart", selected && keyOf(selected), chartType, days, facilityId],
    enabled: selected !== undefined,
    retry: false,
    queryFn: () => {
      const to = new Date();
      const from = new Date(to.getFullYear(), to.getMonth(), to.getDate() - days + 1);
      const query = new URLSearchParams({
        templateId: selected!.templateId,
        parameterCode: selected!.parameterCode,
        from: isoDay(from),
        to: isoDay(to),
      });
      if (chartType) query.set("chartType", chartType);
      if (facilityId) query.set("facilityId", facilityId);
      return api.get<SpcChart>(`/spc/chart?${query}`);
    },
  });

  if (!characteristics.data) {
    return (
      <div className="space-y-6">
        <PageHeader title="SPC" />
        <QueryState error={characteristics.error} />
      </div>
    );
  }

  // One option group a template, its numeric parameters inside.
  const templates = new Map<string, Characteristic[]>();
  for (const characteristic of list) {
    const name = `${characteristic.templateName} (${characteristic.templateCode})`;
    templates.set(name, [...(templates.get(name) ?? []), characteristic]);
  }

  return (
    <div className="max-w-5xl space-y-6">
      <PageHeader title="SPC" subtitle="Control charts and process capability from inspection measurements" />
      {list.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No numeric measurements in the last year. Charts appear once inspections with numeric parameters are submitted.
        </p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="Parameter"
              className={SELECT_CLASS}
              value={selected ? keyOf(selected) : ""}
              onChange={(event) => setChosen(event.target.value)}
            >
              {[...templates].map(([template, parameters]) => (
                <optgroup key={template} label={template}>
                  {parameters.map((parameter) => (
                    <option key={keyOf(parameter)} value={keyOf(parameter)}>
                      {parameter.parameterName ?? parameter.parameterCode} ({parameter.parameterCode}) ·{" "}
                      {parameter.measurements} measurements
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            <select
              aria-label="Period"
              className={SELECT_CLASS}
              value={days}
              onChange={(event) => setDays(Number(event.target.value))}
            >
              {PERIODS.map((period) => (
                <option key={period.days} value={period.days}>
                  {period.label}
                </option>
              ))}
            </select>
            {canSeeFacilities && (facilities.data?.items.length ?? 0) > 1 && (
              <select
                aria-label="Facility"
                className={SELECT_CLASS}
                value={facilityId}
                onChange={(event) => setFacilityId(event.target.value)}
              >
                <option value="">All facilities</option>
                {facilities.data?.items.map((facility) => (
                  <option key={facility.id} value={facility.id}>
                    {facility.name}
                  </option>
                ))}
              </select>
            )}
            <div className="flex gap-1" role="group" aria-label="Chart">
              {CHARTS.map((option) => (
                <Button
                  key={option.label}
                  size="sm"
                  variant={chartType === option.value ? "default" : "outline"}
                  onClick={() => setChartType(option.value)}
                >
                  {option.label}
                </Button>
              ))}
            </div>
          </div>
          {!chart.data ? (
            <QueryState error={chart.error} />
          ) : (
            <SpcResult chart={chart.data} />
          )}
        </>
      )}
    </div>
  );
}

function SpcResult({ chart }: { chart: SpcChart }) {
  const { capability } = chart;
  const verdict = capabilityVerdict(capability.cpk);
  const unit = chart.unit;
  const subgroups = chart.chartType === "XBAR_R";
  const label = (point: SpcChart["points"][number]) => `${point.inspectionNumber} · batch ${point.batchNumber}`;
  const valuePoints: ChartPoint[] = chart.points.map((point, index) => ({
    key: `${point.inspectionId}-${index}`,
    label: label(point),
    measuredAt: point.measuredAt,
    value: point.value,
    outOfControl: point.outOfControl,
  }));
  const rangePoints: ChartPoint[] = chart.points.map((point, index) => ({
    key: `${point.inspectionId}-${index}`,
    label: label(point),
    measuredAt: point.measuredAt,
    value: point.range,
    outOfControl: point.rangeOutOfControl,
  }));
  // Each inspection once, with how many of its values and ranges are out of control.
  const outside = [
    ...chart.points
      .reduce((found, point) => {
        if (!point.outOfControl && !point.rangeOutOfControl) return found;
        const entry = found.get(point.inspectionId) ?? { point, values: 0, ranges: 0 };
        found.set(point.inspectionId, {
          ...entry,
          values: entry.values + (point.outOfControl ? 1 : 0),
          ranges: entry.ranges + (point.rangeOutOfControl ? 1 : 0),
        });
        return found;
      }, new Map<string, { point: SpcChart["points"][number]; values: number; ranges: number }>())
      .values(),
  ];
  const valueWord = subgroups ? "mean" : "value";
  const specText =
    chart.lowerSpec !== null && chart.upperSpec !== null
      ? `${formatMeasure(chart.lowerSpec)} to ${formatMeasure(chart.upperSpec)}`
      : chart.upperSpec !== null
        ? `at most ${formatMeasure(chart.upperSpec)}`
        : chart.lowerSpec !== null
          ? `at least ${formatMeasure(chart.lowerSpec)}`
          : "none";
  const notes = [
    chart.preliminary &&
      `Fewer than 20 ${subgroups ? "inspections" : "measurements"}: the limits are preliminary and may still move.`,
    chart.specChanged && "The specification changed during this period. Capability is judged against the latest one.",
    chart.excludedSubgroups > 0 &&
      `${chart.excludedSubgroups} ${chart.excludedSubgroups === 1 ? "inspection" : "inspections"} with another number of samples than ${chart.subgroupSize} left out.`,
    chart.truncated && "Only the latest 200 inspections are charted. Choose a shorter period to see earlier ones.",
  ].filter(Boolean) as string[];

  return (
    <div className="space-y-6">
      <Section title={`${chart.parameterName ?? chart.parameterCode} · ${chart.templateName}`}>
        <p className="mb-3 text-sm text-muted-foreground">
          {subgroups ? `X̄-R chart, ${chart.subgroupSize} samples an inspection` : "Individuals and moving range chart"}{" "}
          · specification {specText}
          {unit ? ` ${unit}` : ""} · {chart.from} to {chart.to}
        </p>
        <div className="viz-root grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
          <Figure label="Cpk" value={formatIndex(capability.cpk)}>
            {verdict && (
              <div className="mt-0.5 flex items-center gap-1.5 text-xs">
                <span aria-hidden className="size-2 rounded-full" style={{ background: STATUS_COLOUR[verdict.status] }} />
                {verdict.label}
              </div>
            )}
          </Figure>
          <Figure label="Cp" value={formatIndex(capability.cp)} />
          <Figure label="Ppk" value={formatIndex(capability.ppk)} />
          <Figure label="Pp" value={formatIndex(capability.pp)} />
          <Figure label="Mean" value={formatMeasure(capability.mean)} />
          <Figure label="σ within" value={formatMeasure(capability.sigmaWithin)} />
          <Figure label="Measurements" value={String(capability.measurements)} />
        </div>
        {capability.cpk === null && (
          <p className="mt-2 text-sm text-muted-foreground">
            {chart.lowerSpec === null && chart.upperSpec === null
              ? "Capability needs specification limits on the parameter."
              : "Capability needs some variation between measurements."}
          </p>
        )}
        {notes.length > 0 && (
          <ul className="mt-3 list-disc space-y-0.5 pl-5 text-sm text-muted-foreground">
            {notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="Control charts">
        <div className="space-y-8">
          <ControlChart
            title={subgroups ? "Subgroup means (X̄)" : "Individual measurements (I)"}
            valueName={subgroups ? "Mean" : "Measurement"}
            unit={unit}
            points={valuePoints}
            limits={chart.valueLimits}
            spec={{ lower: chart.lowerSpec, upper: chart.upperSpec }}
          />
          <ControlChart
            title={subgroups ? "Subgroup ranges (R)" : "Moving ranges (MR)"}
            valueName={subgroups ? "Range" : "Moving range"}
            unit={unit}
            points={rangePoints}
            limits={chart.rangeLimits}
          />
        </div>
      </Section>
      {outside.length > 0 && (
        <Section title="Points beyond the control limits">
          <p className="mb-2 text-sm text-muted-foreground">
            A point beyond the limits suggests a special cause worth looking into, even when it is within specification.
          </p>
          <ul className="divide-y text-sm">
            {outside.map(({ point, values, ranges }) => (
              <li key={point.inspectionId} className="flex flex-wrap justify-between gap-2 py-2">
                <Link href={`/inspections/${point.inspectionId}`} className="underline underline-offset-4">
                  {point.inspectionNumber}
                </Link>
                <span className="text-muted-foreground">
                  batch {point.batchNumber} · {new Date(point.measuredAt).toLocaleDateString()} ·{" "}
                  {[
                    values > 0 && (values === 1 ? `1 ${valueWord}` : `${values} ${valueWord}s`),
                    ranges > 0 && (ranges === 1 ? "1 range" : `${ranges} ranges`),
                  ]
                    .filter(Boolean)
                    .join(" and ")}{" "}
                  out of control
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
}
