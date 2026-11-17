"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell } from "lucide-react";
import Link from "next/link";
import { useEffect } from "react";
import { api } from "@/lib/api";

/** The stream announces changes at once; the poll only catches up on any it missed. */
const POLL_MILLIS = 5 * 60_000;

/**
 * Listens for changes to the signed-in user's notifications, and re-reads the bell and the inbox when one comes. The
 * event names nothing, so what is shown always comes from the usual inbox requests. The browser reconnects by itself.
 */
export function useNotificationStream(enabled: boolean) {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!enabled || typeof EventSource === "undefined") return;
    const source = new EventSource("/api/v1/notifications/stream");
    source.addEventListener("notifications", () => {
      void queryClient.invalidateQueries({ queryKey: ["/notifications/unread-count"] });
      void queryClient.invalidateQueries({ queryKey: ["/notifications"] });
    });
    return () => source.close();
  }, [enabled, queryClient]);
}

/** Shown twice on a page (the phone header and the menu), so the stream is opened once, by the app shell. */
export function NotificationBell() {
  const { data } = useQuery({
    queryKey: ["/notifications/unread-count"],
    queryFn: () => api.get<{ count: number }>("/notifications/unread-count"),
    refetchInterval: POLL_MILLIS,
  });
  const count = data?.count ?? 0;
  return (
    <Link
      href="/notifications"
      aria-label={count > 0 ? `Notifications, ${count} unread` : "Notifications"}
      className="relative rounded-md p-2 hover:bg-muted"
    >
      <Bell aria-hidden className="size-5" />
      {count > 0 && (
        <span className="absolute -top-0.5 -right-0.5 rounded-full bg-destructive px-1.5 text-xs text-white">
          {count > 99 ? "99+" : count}
        </span>
      )}
    </Link>
  );
}
