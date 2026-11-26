"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { ReportExport } from "@/lib/types";
import { formatDay, formatCalendarDay } from "@/lib/dates";

const PATH = "/reports/exports";
const STATES: Record<ReportExport["status"], string> = {
  QUEUED: "Waiting",
  RUNNING: "Being made",
  READY: "Ready",
  FAILED: "Failed",
  EXPIRED: "Expired",
};

const megabytes = (bytes: number) => `${(bytes / 1_048_576).toFixed(1)} MB`;

/** Asks for the whole report as Excel, made in the background. */
export function ExportInBackground({
  slug,
  period,
}: {
  slug: string;
  period: Record<string, string>;
}) {
  const [state, setState] = useState<"idle" | "pending" | "sent">("idle");
  const [message, setMessage] = useState("");
  if (state === "sent") {
    return (
      <p className="text-sm">
        The export is being made. You will be notified when it is ready, and can
        download it from{" "}
        <Link className="underline" href="/reports/exports">
          your exports
        </Link>
        .
      </p>
    );
  }
  return (
    <div className="space-y-1">
      <FormError message={message} />
      <Button
        size="sm"
        variant="outline"
        disabled={state === "pending"}
        onClick={async () => {
          setState("pending");
          setMessage("");
          try {
            await api.post(`/reports/${slug}/exports`, {
              from: period.from || null,
              to: period.to || null,
              facilityId: period.facilityId || null,
            });
            setState("sent");
          } catch (error) {
            setMessage(
              error instanceof Error
                ? error.message
                : "The export could not be started.",
            );
            setState("idle");
          }
        }}
      >
        Export every row to Excel
      </Button>
    </div>
  );
}

/** The person's own exports. Each is kept for 7 days. */
export function ReportExports() {
  const { data = [] } = useQuery({
    queryKey: [PATH],
    queryFn: () => api.get<ReportExport[]>(PATH),
    refetchInterval: (query) =>
      query.state.data?.some(
        (item) => item.status === "QUEUED" || item.status === "RUNNING",
      )
        ? 3000
        : false,
  });
  if (data.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No exports yet. Start one from a report that is too long to show.
      </p>
    );
  }
  return (
    <ul className="divide-y text-sm">
      {data.map((item) => (
        <li key={item.id} className="flex flex-wrap items-center gap-2 py-2">
          <span>
            {item.title}, {formatCalendarDay(item.from)} to {formatCalendarDay(item.to)}
          </span>
          <StatusBadge
            status={item.status === "FAILED" ? "REJECTED" : item.status}
            label={STATES[item.status]}
          />
          {item.status === "READY" && (
            <>
              <span className="text-muted-foreground">
                {item.rowCount} {item.rowCount === 1 ? "row" : "rows"}
                {item.sizeBytes != null && `, ${megabytes(item.sizeBytes)}`}
                {item.truncated &&
                  ", stopped at the row limit: choose fewer dates for the rest"}
                {item.expiresAt &&
                  `. Kept until ${formatDay(item.expiresAt)}`}
              </span>
              <a className="underline" href={`/api/v1${PATH}/${item.id}/file`}>
                Download Excel
              </a>
            </>
          )}
          {item.failure && (
            <span className="text-muted-foreground">{item.failure}</span>
          )}
        </li>
      ))}
    </ul>
  );
}
