"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api, type PageResponse } from "@/lib/api";
import type { AppNotification } from "@/lib/types";

export default function NotificationsPage() {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const { data } = useQuery({
    queryKey: ["/notifications", unreadOnly],
    queryFn: () => api.get<PageResponse<AppNotification>>(`/notifications?size=50&unreadOnly=${unreadOnly}`),
  });
  const refreshAll = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ["/notifications"] }),
      queryClient.invalidateQueries({ queryKey: ["/notifications/unread-count"] }),
    ]);
  const markAllRead = useMutation({ mutationFn: () => api.post("/notifications/read-all"), onSuccess: refreshAll });
  const open = useMutation({
    mutationFn: (notification: AppNotification) => api.post(`/notifications/${notification.id}/read`),
    onSuccess: async (_, notification) => {
      await refreshAll();
      if (notification.link) router.push(notification.link);
    },
  });
  const items = data?.items ?? [];

  return (
    <div className="max-w-2xl space-y-6">
      <PageHeader title="Notifications">
        <Button variant="outline" onClick={() => setUnreadOnly(!unreadOnly)}>
          {unreadOnly ? "Show all" : "Show unread"}
        </Button>
        <Button variant="outline" disabled={markAllRead.isPending} onClick={() => markAllRead.mutate()}>
          Mark all read
        </Button>
      </PageHeader>
      <FormError message={open.error?.message ?? markAllRead.error?.message} />
      {data && items.length === 0 && <p className="text-sm text-muted-foreground">Nothing to show.</p>}
      <ul className="divide-y rounded-lg border">
        {items.map((notification) => (
          <li key={notification.id}>
            <button
              type="button"
              className="w-full p-3 text-left text-sm hover:bg-muted"
              onClick={() => open.mutate(notification)}
            >
              <span className={notification.read ? "block" : "block font-semibold"}>{notification.title}</span>
              <span className="block text-muted-foreground">{notification.body}</span>
              <span className="block text-xs text-muted-foreground">
                {new Date(notification.createdAt).toLocaleString()}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
