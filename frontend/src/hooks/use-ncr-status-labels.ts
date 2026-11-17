"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

export interface NcrStatusLabel {
  status: string;
  /** The name used when the organisation has not chosen one, such as "Under investigation". */
  standardLabel: string;
  /** The organisation's own name, or null. */
  label: string | null;
  finished: boolean;
}

export const NCR_STATUS_LABELS = "/ncr-status-labels";

export function useNcrStatusLabelList() {
  return useQuery({
    queryKey: [NCR_STATUS_LABELS],
    queryFn: () => api.get<NcrStatusLabel[]>(NCR_STATUS_LABELS),
    staleTime: 5 * 60_000,
  });
}

/**
 * Names an NCR status the organisation's way. Without a name of its own, a status reads as it always has, so an
 * organisation that renames nothing sees no change.
 */
export function useNcrStatusLabel(): (status: string) => string {
  const { data } = useNcrStatusLabelList();
  return (status) =>
    data?.find((entry) => entry.status === status)?.label ?? status.replaceAll("_", " ").toLowerCase();
}
