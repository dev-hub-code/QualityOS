"use client";

import { useQueryClient } from "@tanstack/react-query";
import { CloudOff } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import {
  CHANGED_EVENT,
  discard,
  queuedBy,
  sendQueued,
  type QueuedInspection,
} from "@/lib/offline-results";
import { useConfirm } from "@/components/ui/confirm-dialog";

const RETRY_MS = 30_000;

/**
 * Says when the device is offline, and sends inspection results kept on it once the connection is back: at once,
 * and every half minute while any wait.
 */
export function OfflineResults() {
  const confirm = useConfirm();
  const { data: me } = useMe();
  const queryClient = useQueryClient();
  const [online, setOnline] = useState(true);
  const [items, setItems] = useState<QueuedInspection[]>([]);
  const [sending, setSending] = useState(false);
  const owner = me ? { userId: me.user.id, tenantId: me.tenantId } : null;
  const ownerKey = owner ? `${owner.userId}/${owner.tenantId}` : "";

  useEffect(() => {
    const refresh = () => {
      setOnline(navigator.onLine);
      const [userId, tenantId] = ownerKey.split("/");
      setItems(ownerKey ? queuedBy({ userId, tenantId }) : []);
    };
    refresh();
    window.addEventListener(CHANGED_EVENT, refresh);
    window.addEventListener("storage", refresh);
    window.addEventListener("online", refresh);
    window.addEventListener("offline", refresh);
    return () => {
      window.removeEventListener(CHANGED_EVENT, refresh);
      window.removeEventListener("storage", refresh);
      window.removeEventListener("online", refresh);
      window.removeEventListener("offline", refresh);
    };
  }, [ownerKey]);

  const send = useCallback(
    async (retryRefused: boolean) => {
      if (!ownerKey) return;
      const [userId, tenantId] = ownerKey.split("/");
      setSending(true);
      try {
        const sent = await sendQueued(
          { userId, tenantId },
          (path, body) => api.post(path, body),
          retryRefused,
        );
        if (sent.length > 0) await queryClient.invalidateQueries();
      } finally {
        setSending(false);
      }
    },
    [ownerKey, queryClient],
  );

  const waiting = items.filter((item) => !item.error).length;
  useEffect(() => {
    if (!online || waiting === 0) return;
    const first = window.setTimeout(() => void send(false), 0);
    const timer = window.setInterval(() => void send(false), RETRY_MS);
    return () => {
      window.clearTimeout(first);
      window.clearInterval(timer);
    };
  }, [online, waiting, send]);

  if (online && items.length === 0) return null;
  return (
    <div
      role="status"
      className="mb-4 space-y-2 rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950"
    >
      <p className="flex items-center gap-2 font-medium">
        <CloudOff className="size-4" aria-hidden />
        {online
          ? "Inspection results kept on this device"
          : "No connection. Results you save are kept on this device and sent when it is back."}
      </p>
      {items.length > 0 && (
        <ul className="space-y-1">
          {items.map((item) => (
            <li
              key={item.inspectionId}
              className="flex flex-wrap items-center gap-2"
            >
              <Link
                className="underline"
                href={`/inspections/${item.inspectionId}`}
              >
                {item.inspectionNumber}
              </Link>
              <span>
                {item.entries.length}{" "}
                {item.entries.length === 1 ? "value" : "values"}
                {item.error
                  ? ` not accepted: ${item.error}`
                  : " waiting to be sent"}
              </span>
              {item.error && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    confirm({
                      title: `Discard the values kept for ${item.inspectionNumber}?`,
                      description:
                        "They exist only on this device and were not accepted. Once discarded they are gone, so note them down first if you still need them.",
                      confirmLabel: "Discard values",
                      danger: true,
                    }).then((ok) => {
                      if (ok) discard(item, item.inspectionId);
                    })
                  }
                >
                  Discard
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {online && items.length > 0 && (
        <Button size="sm" disabled={sending} onClick={() => void send(true)}>
          {sending ? "Sending…" : "Send now"}
        </Button>
      )}
    </div>
  );
}
