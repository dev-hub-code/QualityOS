"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormError } from "@/components/forms/field";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import type { NotificationPreference } from "@/lib/types";

const LABELS: Record<string, string> = {
  INSPECTION_AWAITING_APPROVAL: "An inspection is waiting for approval",
  INSPECTION_CRITICAL_FAILURE: "A critical parameter fails",
  INSPECTION_SENT_BACK: "My inspection is sent back",
  INSPECTION_AWAITING_DISPOSITION: "An inspection needs a disposition",
  INSPECTION_DISPOSITION_RECORDED: "A disposition is recorded on my inspection",
};
const PATH = "/notifications/preferences";

export function NotificationPreferences() {
  const queryClient = useQueryClient();
  const { data } = useQuery({ queryKey: [PATH], queryFn: () => api.get<NotificationPreference[]>(PATH) });
  const update = useMutation({
    mutationFn: (preference: NotificationPreference) => api.put(PATH, preference),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [PATH] }),
  });
  return (
    <section className="space-y-3">
      <h2 className="font-medium">Email me when</h2>
      <p className="text-sm text-muted-foreground">These always appear under Notifications in the app.</p>
      <FormError message={update.error?.message} />
      {data?.map(({ type, emailEnabled }) => (
        <div key={type} className="flex items-center gap-2">
          <Checkbox
            id={`email-${type}`}
            checked={emailEnabled}
            disabled={update.isPending}
            onCheckedChange={(checked) => update.mutate({ type, emailEnabled: checked === true })}
          />
          <Label htmlFor={`email-${type}`} className="font-normal">
            {LABELS[type] ?? type}
          </Label>
        </div>
      ))}
    </section>
  );
}
