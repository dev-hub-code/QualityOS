import type { SearchHit } from "@/lib/types";

export function searchHitLink(hit: SearchHit): string {
  return hit.serialNumber
    ? `/traceability/serials/${encodeURIComponent(hit.serialNumber)}`
    : `/traceability/${encodeURIComponent(hit.batchNumber)}`;
}
