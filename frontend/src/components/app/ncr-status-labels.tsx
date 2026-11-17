"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { usePermission } from "@/hooks/use-me";
import { NCR_STATUS_LABELS, useNcrStatusLabelList, type NcrStatusLabel } from "@/hooks/use-ncr-status-labels";
import { api, ApiError } from "@/lib/api";

const MAX_LENGTH = 40;

function NamesForm({ labels, canManage }: { labels: NcrStatusLabel[]; canManage: boolean }) {
  const queryClient = useQueryClient();
  const [names, setNames] = useState<Record<string, string>>(() =>
    Object.fromEntries(labels.map((entry) => [entry.status, entry.label ?? ""])),
  );
  const [message, setMessage] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [pending, setPending] = useState(false);

  async function save() {
    setPending(true);
    setMessage(null);
    setSaved(false);
    try {
      const result = await api.put<NcrStatusLabel[]>(NCR_STATUS_LABELS, { labels: names });
      queryClient.setQueryData([NCR_STATUS_LABELS], result);
      setSaved(true);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    } finally {
      setPending(false);
    }
  }

  return (
    <>
      <div className="grid gap-3 sm:grid-cols-2">
        {labels.map((entry) => (
          <div key={entry.status} className="space-y-1">
            <Label htmlFor={`ncr-status-${entry.status}`}>{entry.standardLabel}</Label>
            <Input
              id={`ncr-status-${entry.status}`}
              placeholder={entry.standardLabel}
              maxLength={MAX_LENGTH}
              disabled={!canManage}
              value={names[entry.status] ?? ""}
              onChange={(event) => {
                setSaved(false);
                setNames((current) => ({ ...current, [entry.status]: event.target.value }));
              }}
            />
          </div>
        ))}
      </div>
      <FormError message={message} />
      {canManage && (
        <div className="flex items-center gap-3">
          <Button disabled={pending} onClick={save}>
            Save status names
          </Button>
          {saved && <span className="text-sm text-muted-foreground">Saved.</span>}
        </div>
      )}
    </>
  );
}

/** Settings: the organisation's own names for the NCR statuses. Only the names change, never the workflow. */
export function NcrStatusLabelSettings() {
  const canManage = usePermission("TENANT_MANAGE");
  const { data } = useNcrStatusLabelList();
  if (!data) return null;
  return (
    <section className="space-y-3">
      <h2 className="text-base font-semibold text-[#1b2a55]">NCR status names</h2>
      <p className="text-sm text-muted-foreground">
        Call each status what your team calls it, such as Quarantine for Containment. The steps, their order and the
        rules for closing an NCR stay the same. Leave a box empty to keep the standard name.
      </p>
      <NamesForm labels={data} canManage={canManage} />
    </section>
  );
}
