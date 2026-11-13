"use client";

import { useQuery } from "@tanstack/react-query";
import { QueryState, Section } from "@/components/records/parts";
import { api } from "@/lib/api";
import { formatMoney } from "@/lib/manufacturing";
import type { CostTotals, WorkOrderCost } from "@/lib/types";

const ROWS: [keyof CostTotals, string][] = [
  ["material", "Material"],
  ["labour", "Labour"],
  ["machine", "Machine"],
  ["overhead", "Overhead"],
  ["total", "Total"],
  ["scrap", "of which scrap"],
  ["rework", "of which rework"],
  ["unit", "Per unit"],
];

/** Planned against actual cost, from purchase prices, lots issued, running time and work centre rates. */
export function WorkOrderCostPanel({ orderId }: { orderId: string }) {
  const path = `/manufacturing/costing/work-orders/${orderId}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<WorkOrderCost>(path) });
  return (
    <Section title="Cost">
      {!data ? (
        <QueryState error={error} />
      ) : (
        <div className="space-y-3 text-sm">
          {!data.complete && (
            <div className="rounded-xl bg-amber-50 px-3 py-2 text-amber-900 ring-1 ring-amber-200">
              <p className="font-medium">Some costs are not known, so the totals cover only what is:</p>
              <ul className="mt-1 list-disc pl-5">
                {data.missing.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}
          <table className="w-full max-w-lg">
            <thead className="text-left text-muted-foreground">
              <tr>
                <th className="py-1 font-medium">{data.currency}</th>
                <th className="py-1 text-right font-medium">Planned</th>
                <th className="py-1 text-right font-medium">Actual</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {ROWS.map(([key, label]) => (
                <tr key={key} className={key === "total" ? "font-medium" : key === "scrap" || key === "rework" ? "text-muted-foreground" : ""}>
                  <td className="py-1.5">{label}</td>
                  <td className="py-1.5 text-right">{formatMoney(data.planned[key], data.currency)}</td>
                  <td className="py-1.5 text-right">{formatMoney(data.actual[key], data.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="text-muted-foreground">
            Actual per-unit cost is known once finished goods are received. Scrap and rework are shares of the total, not
            added to it.
          </p>
        </div>
      )}
    </Section>
  );
}
