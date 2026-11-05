"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { OrganizationSettings } from "@/lib/types";

export const ORGANIZATION_SETTINGS_PATH = "/organizations/current/settings";

type Rules = Pick<OrganizationSettings, "singlePersonReview" | "capaRequiredFrom" | "effectivenessRequiredFrom">;

export function useOrganizationSettings() {
  return useQuery({
    queryKey: [ORGANIZATION_SETTINGS_PATH],
    queryFn: () => api.get<OrganizationSettings>(ORGANIZATION_SETTINGS_PATH),
  });
}

/** Saves a change to one or more rules. The server takes every rule, so the rest are sent as last read. */
export function useSaveOrganizationSettings() {
  const queryClient = useQueryClient();
  const { data } = useOrganizationSettings();
  return useMutation({
    mutationFn: (changes: Partial<Rules>) => {
      if (!data) throw new Error("Settings are still loading.");
      const { singlePersonReview, capaRequiredFrom, effectivenessRequiredFrom, version } = data;
      return api.put(ORGANIZATION_SETTINGS_PATH, {
        singlePersonReview,
        capaRequiredFrom,
        effectivenessRequiredFrom,
        version,
        ...changes,
      });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: [ORGANIZATION_SETTINGS_PATH] }),
  });
}

/** A sole member may review their own work right now, giving a reason each time. */
export function useSelfReviewInEffect(): boolean {
  return useOrganizationSettings().data?.selfReviewInEffect ?? false;
}
