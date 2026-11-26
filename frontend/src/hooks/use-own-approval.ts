"use client";

import { useQuery } from "@tanstack/react-query";
import { useMe } from "@/hooks/use-me";
import { useSelfReviewInEffect } from "@/hooks/use-organization-settings";
import { api } from "@/lib/api";
import type { ManufacturingSettings } from "@/lib/types";

/**
 * Whether the person looking is the submitter and the manufacturing rules need someone else to approve. The server
 * refuses the approval either way; this keeps the button from being offered only to be refused.
 */
export function useOwnApprovalRefused(submittedBy: string | null): boolean {
  const { data: me } = useMe();
  const selfReview = useSelfReviewInEffect();
  const own = Boolean(me && submittedBy === me.user.id);
  // Asked only for one's own submission, and only by someone who may read the settings.
  const { data: settings } = useQuery({
    queryKey: ["/manufacturing/settings"],
    queryFn: () => api.get<ManufacturingSettings>("/manufacturing/settings"),
    enabled: own && Boolean(me?.permissions.includes("MANUFACTURING_VIEW")),
  });
  if (!own) return false;
  return (settings?.separateApprover ?? true) && !selfReview;
}
