"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { OrganizationSettings } from "@/lib/types";

export const ORGANIZATION_SETTINGS_PATH = "/organizations/current/settings";

export function useOrganizationSettings() {
  return useQuery({
    queryKey: [ORGANIZATION_SETTINGS_PATH],
    queryFn: () => api.get<OrganizationSettings>(ORGANIZATION_SETTINGS_PATH),
  });
}

/** A sole member may review their own work right now, giving a reason each time. */
export function useSelfReviewInEffect(): boolean {
  return useOrganizationSettings().data?.selfReviewInEffect ?? false;
}
