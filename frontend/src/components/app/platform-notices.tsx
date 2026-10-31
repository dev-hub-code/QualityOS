"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { CustomerNotices } from "@/lib/types";

const STORE = "dismissed-announcements";
const TONE: Record<string, string> = {
  INFO: "bg-muted",
  WARNING: "border-amber-500 bg-amber-50 dark:bg-amber-950",
  CRITICAL: "border-destructive bg-red-50 dark:bg-red-950",
};

function remembered(): string[] {
  try {
    return JSON.parse(window.localStorage.getItem(STORE) ?? "[]");
  } catch {
    return [];
  }
}

export function PlatformNotices() {
  const [dismissed, setDismissed] = useState<string[]>(() => (typeof window === "undefined" ? [] : remembered()));
  const { data } = useQuery({
    queryKey: ["/platform-notices"],
    queryFn: () => api.get<CustomerNotices>("/platform-notices"),
    refetchInterval: 60_000,
  });
  if (!data) return null;
  function dismiss(id: string) {
    const next = [...dismissed, id];
    setDismissed(next);
    try {
      window.localStorage.setItem(STORE, JSON.stringify(next));
    } catch {
      // Without storage it comes back next visit, which does no harm.
    }
  }
  return (
    <>
      {data.maintenance && (
        <p className="mb-4 rounded-md border border-amber-500 bg-amber-50 px-3 py-2 text-sm dark:bg-amber-950">
          QualityOS is under maintenance. You can read your records, but changes are paused.
          {data.supportEmail ? ` Questions? ${data.supportEmail}` : ""}
        </p>
      )}
      {data.announcements.filter((item) => !dismissed.includes(item.id)).map((item) => (
        <section key={item.id} aria-label="Announcement" className={`mb-4 flex items-start justify-between gap-3 rounded-md border px-3 py-2 text-sm ${TONE[item.severity]}`}>
          <p>
            <span className="font-medium">{item.title}.</span> {item.message}
          </p>
          {item.severity !== "CRITICAL" && (
            <Button size="sm" variant="ghost" onClick={() => dismiss(item.id)}>Dismiss</Button>
          )}
        </section>
      ))}
    </>
  );
}
