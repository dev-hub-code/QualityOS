"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PageHeader, QueryState, RecordLink } from "@/components/records/parts";
import { api } from "@/lib/api";
import { searchHitLink } from "@/lib/search";
import type { SearchHit } from "@/lib/types";

function Results() {
  const term = useSearchParams().get("q") ?? "";
  const path = `/traceability/search?q=${encodeURIComponent(term)}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<SearchHit[]>(path),
    enabled: term.length > 0,
  });
  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader title="Search" subtitle={`Results for “${term}”`} />
      {!data && <QueryState error={error} />}
      {data?.length === 0 && <p className="text-sm text-muted-foreground">Nothing matches.</p>}
      <ul className="divide-y text-sm">
        {data?.map((hit) => (
          <li key={hit.serialNumber ?? hit.batchNumber} className="py-3">
            <RecordLink href={searchHitLink(hit)}>{hit.serialNumber ?? hit.batchNumber}</RecordLink>
            <p>{hit.description}</p>
            <p className="text-muted-foreground">{hit.matchedOn}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function SearchPage() {
  return (
    <Suspense>
      <Results />
    </Suspense>
  );
}
