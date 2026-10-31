"use client";

import { useQuery } from "@tanstack/react-query";
import { Bell } from "lucide-react";
import Link from "next/link";
import { api } from "@/lib/api";

const POLL_MILLIS = 60_000;

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
