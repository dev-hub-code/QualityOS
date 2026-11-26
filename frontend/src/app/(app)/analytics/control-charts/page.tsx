"use client";

import { formatCalendarDay } from "@/lib/dates";
import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { ControlChart } from "@/components/spc/control-chart";
import { CapabilitySummary, SignalList, SpcAssumptions } from "@/components/spc/spc-summary";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import type { ChartChoice, PanelPoint, SpcChart, SpcParameter } from "@/lib/spc";
import type { Facility } from "@/lib/types";

const SELECT_CLASS = "h-9 rounded-xl border border-input bg-white/70 px-2 text-sm";
const PERIODS = [
  { days: 90, label: "Last 90 days" },
  { days: 180, label: "Last 6 months" },
  { days: 365, label: "Last 12 months" },
  { days: 730, label: "Last 2 years" },
] as const;
const CHARTS: { value: ChartChoice; label: string }[] = [
  { value: "AUTO", label: "Auto" },
  { value: "XBAR_R", label: "X̄-R" },
  { value: "I_MR", label: "Individuals (I-MR)" },
];

interface Item {
  id: string;
  code: string;
  name: string;
}

const keyOf = (parameter: SpcParameter) => `${parameter.templateId}/${parameter.parameterCode}`;

function isoDay(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function period(days: number): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getFullYear(), to.getMonth(), to.getDate() - days + 1);
  return { from: isoDay(from), to: isoDay(to) };
}

export default function ControlChartsPage() {
  const canSeeFacilities = usePermission("FACILITY_VIEW");
  const canSeeItems = usePermission("PRODUCT_VIEW");
  const [chosen, setChosen] = useState<string | null>(null);
  const [chart, setChart] = useState<ChartChoice>("AUTO");
  const [days, setDays] = useState<number>(PERIODS[0].days);
  const [facilityId, setFacilityId] = useState("");
  /** "m:<id>" for a material, "p:<id>" for a product. */
  const [item, setItem] = useState("");
  const { from, to } = period(days);

  const scope = new URLSearchParams({ from, to });
  if (facilityId) scope.set("facilityId", facilityId);
  const parameters = useQuery({
    queryKey: ["/spc/parameters", scope.toString()],
    queryFn: () => api.get<SpcParameter[]>(`/spc/parameters?${scope}`),
  });
  const facilities = useQuery({
    queryKey: ["/facilities", "control-charts"],
    queryFn: () => api.get<PageResponse<Facility>>("/facilities?size=100"),
    enabled: canSeeFacilities,
  });
  const materials = useQuery({
    queryKey: ["/materials", "control-charts"],
    queryFn: () => api.get<PageResponse<Item>>("/materials?size=100"),
    enabled: canSeeItems,
  });
  const products = useQuery({
    queryKey: ["/products", "control-charts"],
    queryFn: () => api.get<PageResponse<Item>>("/products?size=100"),
    enabled: canSeeItems,
  });
  const list = parameters.data ?? [];
  const selected = list.find((parameter) => keyOf(parameter) === chosen) ?? list[0];

  const query = new URLSearchParams(scope);
  if (selected) {
    query.set("templateId", selected.templateId);
    query.set("parameterCode", selected.parameterCode);
  }
  query.set("chart", chart);
  if (item.startsWith("m:")) query.set("materialId", item.slice(2));
  if (item.startsWith("p:")) query.set("productId", item.slice(2));
  const result = useQuery({
    queryKey: ["/spc/chart", query.toString()],
    enabled: selected !== undefined,
    retry: false,
    queryFn: () => api.get<SpcChart>(`/spc/chart?${query}`),
  });

  return (
    <div className="max-w-5xl space-y-6">
      <PageHeader title="Control charts" subtitle="Pick a parameter, read Cp and Cpk, see every signal" />
      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label="Parameter"
          className={SELECT_CLASS}
          value={selected ? keyOf(selected) : ""}
          onChange={(event) => setChosen(event.target.value)}
          disabled={list.length === 0}
        >
          {list.length === 0 && <option value="">No parameters with readings</option>}
          {list.map((parameter) => (
            <option key={keyOf(parameter)} value={keyOf(parameter)}>
              {parameter.templateCode} · {parameter.parameterCode}
              {parameter.parameterName ? ` (${parameter.parameterName})` : ""} · {parameter.subgroups}{" "}
              {parameter.subgroups === 1 ? "inspection" : "inspections"}
            </option>
          ))}
        </select>
        <select aria-label="Period" className={SELECT_CLASS} value={days} onChange={(event) => setDays(Number(event.target.value))}>
          {PERIODS.map((option) => (
            <option key={option.days} value={option.days}>
              {option.label}
            </option>
          ))}
        </select>
        {canSeeFacilities && (facilities.data?.items.length ?? 0) > 1 && (
          <select aria-label="Facility" className={SELECT_CLASS} value={facilityId} onChange={(event) => setFacilityId(event.target.value)}>
            <option value="">All facilities</option>
            {facilities.data?.items.map((facility) => (
              <option key={facility.id} value={facility.id}>
                {facility.name}
              </option>
            ))}
          </select>
        )}
        {canSeeItems && (
          <select aria-label="Material or product" className={SELECT_CLASS} value={item} onChange={(event) => setItem(event.target.value)}>
            <option value="">Any material or product</option>
            {(materials.data?.items.length ?? 0) > 0 && (
              <optgroup label="Materials">
                {materials.data?.items.map((material) => (
                  <option key={material.id} value={`m:${material.id}`}>
                    {material.code} · {material.name}
                  </option>
                ))}
              </optgroup>
            )}
            {(products.data?.items.length ?? 0) > 0 && (
              <optgroup label="Products">
                {products.data?.items.map((product) => (
                  <option key={product.id} value={`p:${product.id}`}>
                    {product.code} · {product.name}
                  </option>
                ))}
              </optgroup>
            )}
          </select>
        )}
        <select aria-label="Chart" className={SELECT_CLASS} value={chart} onChange={(event) => setChart(event.target.value as ChartChoice)}>
          {CHARTS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      {!parameters.data ? (
        <QueryState error={parameters.error} />
      ) : list.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No numeric readings in this period. A parameter appears here once inspections that measure it are submitted.
        </p>
      ) : !result.data ? (
        <QueryState error={result.error} />
      ) : (
        <ChartResult chart={result.data} />
      )}
    </div>
  );
}

function ChartResult({ chart }: { chart: SpcChart }) {
  const router = useRouter();
  const subgroups = chart.chartType === "XBAR_R";
  const label = (point: SpcChart["points"][number]) =>
    `${point.inspectionNumber}${point.sampleNumber !== undefined ? ` #${point.sampleNumber}` : ""} · batch ${point.batchNumber}`;
  const values: PanelPoint[] = chart.points.map((point, index) => ({
    key: `${point.inspectionId}-${index}`,
    label: label(point),
    inspectionId: point.inspectionId,
    submittedAt: point.submittedAt,
    value: point.value,
    centre: chart.centre,
    lower: point.lcl,
    upper: point.ucl,
    signal: point.signals.includes("BEYOND_LIMITS") || point.signals.includes("RUN_OF_8"),
  }));
  const ranges: PanelPoint[] = chart.points.map((point, index) => ({
    key: `${point.inspectionId}-${index}`,
    label: label(point),
    inspectionId: point.inspectionId,
    submittedAt: point.submittedAt,
    value: point.range,
    centre: point.rangeCentre,
    lower: point.rangeLcl,
    upper: point.rangeUcl,
    signal: point.signals.includes("RANGE_BEYOND_LIMITS"),
  }));
  const open = (point: PanelPoint) => router.push(`/inspections/${point.inspectionId}`);
  const name = chart.parameter.name ?? chart.parameter.code;

  return (
    <div className="space-y-6">
      <Section title={`${chart.templateCode} · ${name}`}>
        <p className="mb-3 text-sm text-muted-foreground">
          {subgroups ? "X̄-R chart, one subgroup per inspection" : "Individuals and moving range chart, one point per reading"}{" "}
          · {chart.points.length} {chart.points.length === 1 ? "point" : "points"} · {formatCalendarDay(chart.from)} to {formatCalendarDay(chart.to)}
        </p>
        <CapabilitySummary chart={chart} />
        <div className="mt-3">
          <SpcAssumptions chart={chart} />
        </div>
      </Section>
      <Section title="Charts">
        <div className="space-y-8">
          <ControlChart
            title={subgroups ? "X̄ chart · subgroup means" : "Individuals"}
            valueName={subgroups ? "Mean" : "Reading"}
            unit={chart.parameter.unit}
            points={values}
            spec={{ lower: chart.parameter.lsl, upper: chart.parameter.usl, version: chart.parameter.specVersion }}
            onOpen={open}
          />
          <ControlChart
            title={subgroups ? "R chart · subgroup ranges" : "Moving range"}
            valueName={subgroups ? "Range" : "Moving range"}
            unit={chart.parameter.unit}
            points={ranges}
            onOpen={open}
          />
        </div>
      </Section>
      <Section title="Signals">
        <SignalList chart={chart} />
      </Section>
    </div>
  );
}
