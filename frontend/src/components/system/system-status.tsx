"use client";

import { useQuery } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, CircleX, LoaderCircle } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import type { SystemState, SystemStatusReport } from "@/lib/system-status";

const STATE_DISPLAY = {
  operational: {
    label: "All systems operational",
    detail: "The API and database are ready.",
    icon: CircleCheck,
    className: "text-success",
  },
  "not-ready": {
    label: "Starting up or degraded",
    detail: "The API is running but its database is not ready.",
    icon: CircleAlert,
    className: "text-warning",
  },
  unreachable: {
    label: "API unreachable",
    detail: "The QualityOS API did not respond.",
    icon: CircleX,
    className: "text-destructive",
  },
} satisfies Record<SystemState, object>;

async function loadStatus(): Promise<SystemStatusReport> {
  const response = await fetch("/api/system/status");
  if (!response.ok) {
    throw new Error(`Status request failed: ${response.status}`);
  }
  return response.json();
}

export function SystemStatus() {
  const { data } = useQuery({
    queryKey: ["system-status"],
    queryFn: loadStatus,
    refetchInterval: 15_000,
  });

  const display = data ? STATE_DISPLAY[data.state] : null;
  const Icon = display?.icon ?? LoaderCircle;

  return (
    <Card>
      <CardHeader>
        <CardTitle>System status</CardTitle>
      </CardHeader>
      <CardContent className="flex items-start gap-3" aria-live="polite">
        <Icon
          aria-hidden
          className={`mt-0.5 size-5 shrink-0 ${display?.className ?? "animate-spin text-muted-foreground"}`}
        />
        <div className="space-y-1">
          <p className="font-medium">{display?.label ?? "Checking…"}</p>
          {display && <p className="text-sm text-muted-foreground">{display.detail}</p>}
          {data?.version && (
            <p className="text-sm text-muted-foreground">Version {data.version}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
