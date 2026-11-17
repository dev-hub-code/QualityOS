"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";

type State = "NORMAL" | "TIGHTENED" | "REDUCED" | "DISCONTINUED";

interface Series {
  supplierId: string | null;
  supplierName: string | null;
  state: State;
  recentResults: string;
  switchingScore: number;
  mayReduce: boolean;
  tightenedAcceptedInRow: number;
  tightenedRejected: number;
}

/** The ISO 2859-1 label for an inspection level: S1 is shown as S-1. */
export function levelLabel(level: string | null) {
  return level?.startsWith("S") ? `S-${level.slice(1)}` : (level ?? "");
}

function standing(series: Series) {
  switch (series.state) {
    case "NORMAL":
      return `Switching score ${series.switchingScore}${series.mayReduce ? ", enough for reduced inspection" : ""}. Last lots: ${
        series.recentResults ? series.recentResults.replaceAll("A", "✓").replaceAll("R", "✗") : "none yet"
      }.`;
    case "TIGHTENED":
      return `${series.tightenedAcceptedInRow} of 5 lots accepted in a row to return to normal; ${series.tightenedRejected} of 5 rejections before inspection is discontinued.`;
    case "REDUCED":
      return "A rejected lot, or one accepted with more than its acceptance number, returns it to normal.";
    case "DISCONTINUED":
      return "Five lots were rejected on tightened inspection. No lot is inspected by AQL until it is resumed.";
  }
}

/** Where each series of lots under an AQL template stands, and a manager's switches. */
export function AqlSwitching({ templateId }: { templateId: string }) {
  const path = `/inspection-templates/${templateId}/aql-switching`;
  const canManage = usePermission("TEMPLATE_MANAGE");
  const refresh = useRefresh();
  const [switching, setSwitching] = useState<{ series: Series; to: State; title: string } | null>(null);
  const { data = [] } = useQuery({ queryKey: [path], queryFn: () => api.get<Series[]>(path) });
  const actions = (series: Series): { to: State; label: string }[] => {
    switch (series.state) {
      case "NORMAL":
        return [
          ...(series.mayReduce ? [{ to: "REDUCED" as State, label: "Reduce" }] : []),
          { to: "TIGHTENED", label: "Tighten" },
        ];
      case "TIGHTENED":
        return [];
      case "REDUCED":
        return [
          { to: "NORMAL", label: "Back to normal" },
          { to: "TIGHTENED", label: "Tighten" },
        ];
      case "DISCONTINUED":
        return [{ to: "TIGHTENED", label: "Resume on tightened" }];
    }
  };
  return (
    <Section title="AQL switching">
      <p className="mb-3 text-sm text-muted-foreground">
        Each supplier&apos;s lots, and production lots, move between normal, tightened and reduced inspection as they
        are decided: two rejections in five lots tighten, five acceptances in a row relax, and five rejections while
        tightened discontinue inspection. Reduced inspection needs a switching score of 30 and someone&apos;s decision
        that production is steady.
      </p>
      {data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No lot has been decided under this template yet; all start on normal inspection.</p>
      ) : (
        <ul className="divide-y text-sm">
          {data.map((series) => (
            <li key={series.supplierId ?? "production"} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <div className="space-y-1">
                <p className="font-medium">{series.supplierName ?? "Production lots"}</p>
                <p className="text-muted-foreground">{standing(series)}</p>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge status={series.state} />
                {canManage &&
                  actions(series).map((action) => (
                    <Button
                      key={action.to}
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        setSwitching({
                          series,
                          to: action.to,
                          title: `${action.label}: ${series.supplierName ?? "production lots"}`,
                        })
                      }
                    >
                      {action.label}
                    </Button>
                  ))}
              </div>
            </li>
          ))}
        </ul>
      )}
      {switching && (
        <RecordDialog
          title={switching.title}
          fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
          onClose={() => setSwitching(null)}
          onSubmit={(values) =>
            api
              .post(path, { supplierId: switching.series.supplierId, state: switching.to, reason: values.reason })
              .then(refresh)
          }
        />
      )}
    </Section>
  );
}
