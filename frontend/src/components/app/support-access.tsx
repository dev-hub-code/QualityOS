"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { usePermission } from "@/hooks/use-me";
import { api, ApiError } from "@/lib/api";

interface Grant {
  id: string;
  grantedAt: string;
  expiresAt: string;
  grantedByName: string | null;
  note: string | null;
  revokedAt: string | null;
  revokedByName: string | null;
  open: boolean;
  allowChanges: boolean;
}

interface Overview {
  current: Grant | null;
  grants: Grant[];
  events: { operatorName: string; kind: "STARTED" | "VIEWED" | "CHANGED"; detail: string; occurredAt: string }[];
}

const PATH = "/support-access";
const DURATIONS = [
  { hours: 24, label: "24 hours" },
  { hours: 72, label: "3 days" },
  { hours: 168, label: "7 days" },
];

const when = (at: string) => new Date(at).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });

/** Changes are allowed for at most this long. */
const CHANGES_MAX_HOURS = 24;

/**
 * Settings: let ProductionOS support read the organisation's records for a while, or also make changes for a day at
 * most, and see what it read and changed.
 */
export function SupportAccessSettings() {
  const canManage = usePermission("TENANT_MANAGE");
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: [PATH], queryFn: () => api.get<Overview>(PATH), enabled: canManage });
  const [hours, setHours] = useState(24);
  const [allowChanges, setAllowChanges] = useState(false);
  const [note, setNote] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  if (!canManage || !data) return null;

  async function run(action: () => Promise<Overview>) {
    setPending(true);
    setMessage(null);
    try {
      queryClient.setQueryData([PATH], await action());
      setNote("");
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "Something went wrong. Please try again.");
    } finally {
      setPending(false);
    }
  }

  const { current } = data;
  return (
    <section className="space-y-3">
      <h2 className="text-base font-semibold text-[#1b2a55]">Support access</h2>
      <p className="text-sm text-muted-foreground">
        ProductionOS support cannot see your records unless you let it. Access is read-only unless you also let support
        make changes, for 24 hours at most. Either way it ends by itself, and you can withdraw it at any time.
        Approvals, sign-offs, closures, user access and billing always stay with your own people. Every page support
        opens and every change it makes is listed below, and changes show as support&apos;s in the audit log.
      </p>
      {current ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-amber-50 p-3 text-sm text-amber-900 ring-1 ring-amber-200">
          <span>
            Support can {current.allowChanges ? "read and change" : "read"} your records until{" "}
            <strong>{when(current.expiresAt)}</strong>
            {current.grantedByName && `, as ${current.grantedByName} allowed`}
            {current.note && ` (${current.note})`}.
          </span>
          <Button variant="outline" disabled={pending} onClick={() => run(() => api.post<Overview>(`${PATH}/revoke`))}>
            Withdraw access
          </Button>
        </div>
      ) : (
        <p className="text-sm">Support has no access.</p>
      )}
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <Label htmlFor="support-duration">{current ? "Replace with access for" : "Allow access for"}</Label>
          <select
            id="support-duration"
            className="h-9 rounded-md border bg-white px-2 text-sm"
            value={hours}
            onChange={(event) => setHours(Number(event.target.value))}
          >
            {DURATIONS.filter((duration) => !allowChanges || duration.hours <= CHANGES_MAX_HOURS).map((duration) => (
              <option key={duration.hours} value={duration.hours}>
                {duration.label}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="support-level">Support may</Label>
          <select
            id="support-level"
            className="h-9 rounded-md border bg-white px-2 text-sm"
            value={allowChanges ? "changes" : "view"}
            onChange={(event) => {
              const changes = event.target.value === "changes";
              setAllowChanges(changes);
              if (changes) setHours(Math.min(hours, CHANGES_MAX_HOURS));
            }}
          >
            <option value="view">View only</option>
            <option value="changes">View and make changes (24 hours at most)</option>
          </select>
        </div>
        <div className="min-w-48 flex-1 space-y-1">
          <Label htmlFor="support-note">Note for support (optional)</Label>
          <Input id="support-note" maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
        </div>
        <Button
          disabled={pending}
          onClick={() => run(() => api.post<Overview>(PATH, { durationHours: hours, note: note || null, allowChanges }))}
        >
          Allow support access
        </Button>
      </div>
      <FormError message={message} />
      {data.events.length > 0 && (
        <div className="space-y-1">
          <h3 className="text-sm font-medium">What support did</h3>
          <ul className="max-h-64 divide-y overflow-y-auto text-sm">
            {data.events.map((event, index) => (
              <li key={`${event.occurredAt}-${index}`} className="flex justify-between gap-3 py-1.5">
                <span className="min-w-0 break-words">
                  {event.kind === "STARTED" ? (
                    <>
                      {event.operatorName} opened a support session: <em>{event.detail}</em>
                    </>
                  ) : event.kind === "CHANGED" ? (
                    <>
                      {event.operatorName} changed <code className="text-xs">{event.detail}</code>
                    </>
                  ) : (
                    <>
                      {event.operatorName} viewed <code className="text-xs">{event.detail}</code>
                    </>
                  )}
                </span>
                <span className="shrink-0 text-muted-foreground">{when(event.occurredAt)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
