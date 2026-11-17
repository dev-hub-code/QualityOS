"use client";

import { StatusBadge } from "@/components/records/parts";
import { useNcrStatusLabel } from "@/hooks/use-ncr-status-labels";

/** An NCR's status, under the name the organisation gave it. */
export function NcrStatusBadge({ status }: { status: string }) {
  const label = useNcrStatusLabel();
  return <StatusBadge status={status} label={label(status)} />;
}
