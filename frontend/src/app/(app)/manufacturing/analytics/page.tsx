"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { RankBars } from "@/components/analytics/charts";
import { PeriodFilter, periodQuery } from "@/components/analytics/period-filter";
import { PageHeader, QueryState, Section, StatusBadge, useUnits } from "@/components/records/parts";
import { api } from "@/lib/api";
import { formatMinutes, formatMoney, formatPercent, localDate, LOSS_CATEGORY_LABELS } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { LossCategory, MachineOee, ProductionAnalytics } from "@/lib/types";

function Tiles({ tiles }: { tiles: [string, string | number][] }) {
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {tiles.map(([label, value]) => (
        <div key={label} className="glass rounded-2xl p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-xl font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

const OEE_COLUMNS: [keyof MachineOee, string][] = [
  ["availability", "Availability"],
  ["performance", "Performance"],
  ["quality", "Quality"],
  ["oee", "OEE"],
  ["utilisation", "Utilisation"],
];

function OeeRow({ machine, label }: { machine: MachineOee; label: string }) {
  return (
    <tr className="align-top">
      <td className="py-1.5 pr-3">
        <span className="font-medium">{label}</span>
        <span className="block text-xs text-muted-foreground">
          Running {formatMinutes(machine.runningMinutes)} of {formatMinutes(machine.plannedProductionMinutes)} planned ·
          unplanned stops {formatMinutes(machine.unplannedDowntimeMinutes)}
        </span>
        {machine.notes.length > 0 && (
          <ul className="mt-1 list-disc pl-4 text-xs text-amber-800">
            {machine.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </td>
      {OEE_COLUMNS.map(([key]) => (
        <td key={key} className={`py-1.5 pr-3 whitespace-nowrap ${key === "oee" ? "font-semibold" : ""}`}>
          {formatPercent(machine[key] as string | null | undefined)}
        </td>
      ))}
    </tr>
  );
}

/** What production made, how well, at what cost, and where time was lost. Figures without data say so. */
export default function ProductionAnalyticsPage() {
  const units = useUnits();
  const [period, setPeriod] = useState({ from: localDate(-29), to: localDate(), facilityId: "" });
  const path = `/manufacturing/analytics?${periodQuery(period)}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<ProductionAnalytics>(path),
    placeholderData: keepPreviousData,
    enabled: Boolean(period.from && period.to),
    retry: false,
  });

  return (
    <div className="space-y-6">
      <PageHeader
        title="Production analytics"
        subtitle={data ? `${data.from} to ${data.to}, days in ${data.timezone}` : "Choose a period"}
      />
      <PeriodFilter value={period} onChange={setPeriod} />
      {error && <QueryState error={error} />}
      {!data && !error && <QueryState error={null} />}
      {data && (
        <>
          <Tiles
            tiles={[
              ["OEE", formatPercent(data.overall.oee)],
              ["Orders completed", data.ordersCompleted],
              ["Closed short", data.ordersPartiallyCompleted],
              ["Finished on time", data.ordersOnTime],
              ["Efficiency", formatPercent(data.efficiency.percent)],
              ["Rejection rate", formatPercent(data.quality.rejectionRate)],
              ["Scrap rate", formatPercent(data.quality.scrapRate)],
              ["Rework rate", formatPercent(data.quality.reworkRate)],
            ]}
          />
          <div className="grid gap-6 lg:grid-cols-2">
            <Section title="Produced">
              {data.production.length === 0 ? (
                <p className="text-sm text-muted-foreground">No finished goods received in this period.</p>
              ) : (
                <ul className="divide-y text-sm">
                  {data.production.map((line) => (
                    <li key={line.productId} className="flex justify-between gap-3 py-1.5">
                      <span>
                        {line.productCode} – {line.productName}
                      </span>
                      <span className="font-medium">
                        {formatQuantity(line.received ?? null)} {units.label(line.uomId)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Section>
            <Section title="Planned against actual (orders closed)">
              {data.plannedVersusActual.length === 0 ? (
                <p className="text-sm text-muted-foreground">No orders closed in this period.</p>
              ) : (
                <ul className="divide-y text-sm">
                  {data.plannedVersusActual.map((line) => (
                    <li key={line.productId} className="flex justify-between gap-3 py-1.5">
                      <span>
                        {line.productCode} · {line.orders} {line.orders === 1 ? "order" : "orders"}
                      </span>
                      <span>
                        {formatQuantity(line.received ?? null)} of {formatQuantity(line.ordered ?? null)} {units.label(line.uomId)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Section>
            <Section title="Work orders now">
              <ul className="flex flex-wrap gap-3 text-sm">
                {data.workOrderStatus.map((row) => (
                  <li key={row.status} className="flex items-center gap-1.5">
                    <StatusBadge status={row.status} /> {row.count}
                  </li>
                ))}
              </ul>
            </Section>
            <Section title="Quality and material">
              <dl className="grid grid-cols-2 gap-2 text-sm">
                <dt className="text-muted-foreground">Reported</dt>
                <dd>{formatQuantity(data.quality.processed)}</dd>
                <dt className="text-muted-foreground">Good · rejected · scrap</dt>
                <dd>
                  {formatQuantity(data.quality.good)} · {formatQuantity(data.quality.rejected)} · {formatQuantity(data.quality.scrap)}
                </dd>
                <dt className="text-muted-foreground">Sent to rework</dt>
                <dd>{formatQuantity(data.quality.rework)}</dd>
                <dt className="text-muted-foreground">Running time against plan</dt>
                <dd>
                  {formatMinutes(data.efficiency.actualMinutes)} for {formatMinutes(data.efficiency.plannedMinutes)} planned (
                  {data.efficiency.operations} operations)
                </dd>
                <dt className="text-muted-foreground">Material lines outside tolerance</dt>
                <dd>
                  {data.materialVariance.linesOutside} of {data.materialVariance.lines} at {data.materialVariance.receipts} receipts
                </dd>
                <dt className="text-muted-foreground">Average material variance</dt>
                <dd>{formatPercent(data.materialVariance.averageAbsolutePercent)}</dd>
              </dl>
            </Section>
          </div>
          <Section title="OEE by machine">
            <p className="mb-2 text-xs text-muted-foreground">
              Availability is running time over shift time less planned downtime; performance is the routing&apos;s run time
              per unit × units reported over running time; quality is good over reported; OEE is their product.
            </p>
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-left text-muted-foreground">
                  <tr>
                    <th className="py-1 pr-3 font-medium">Machine</th>
                    {OEE_COLUMNS.map(([key, label]) => (
                      <th key={key} className="py-1 pr-3 font-medium">
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {data.machines.map((machine) => (
                    <OeeRow key={machine.machineId} machine={machine} label={`${machine.machineCode} · ${machine.workCenterCode}`} />
                  ))}
                  <OeeRow machine={data.overall} label="All machines" />
                </tbody>
              </table>
            </div>
          </Section>
          <div className="grid gap-6 lg:grid-cols-2">
            <Section title="Downtime by category">
              {data.downtimeByCategory.length === 0 ? (
                <p className="text-sm text-muted-foreground">No downtime in this period.</p>
              ) : (
                <RankBars
                  headings={["Category", "Time down"]}
                  rows={data.downtimeByCategory.map((row) => ({
                    label: LOSS_CATEGORY_LABELS[row.key as LossCategory] ?? row.label,
                    value: Number(row.minutes),
                    display: formatMinutes(row.minutes),
                    note: `${row.stops} ${row.stops === 1 ? "stop" : "stops"}`,
                  }))}
                />
              )}
              <Link className="mt-2 inline-block text-sm text-primary hover:underline" href="/manufacturing/downtime">
                Every stop
              </Link>
            </Section>
            {data.cost && (
              <Section title="Cost of orders closed">
                <p className="mb-2 text-sm">
                  Planned {formatMoney(data.cost.plannedTotal, data.cost.currency)} · actual{" "}
                  {formatMoney(data.cost.actualTotal, data.cost.currency)}
                </p>
                {!data.cost.complete && (
                  <p className="mb-2 text-sm text-amber-800">Some orders lack a price or rate, so these totals are partial.</p>
                )}
                <ul className="divide-y text-sm">
                  {data.cost.orders.map((order) => (
                    <li key={order.workOrderId} className="flex flex-wrap justify-between gap-3 py-1.5">
                      <Link className="text-primary hover:underline" href={`/manufacturing/work-orders/${order.workOrderId}`}>
                        {order.workOrderNumber} · {order.productCode}
                      </Link>
                      <span>
                        {formatMoney(order.actualUnit, data.cost!.currency)} a unit against{" "}
                        {formatMoney(order.plannedUnit, data.cost!.currency)} planned
                        {!order.complete && " (partial)"}
                      </span>
                    </li>
                  ))}
                </ul>
              </Section>
            )}
          </div>
        </>
      )}
    </div>
  );
}
