"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { PageHeader, QueryState, RecordLink } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { searchHitLink } from "@/lib/search";
import type { SearchHit } from "@/lib/types";

const KINDS: Record<string, string> = {
  LOT: "Received lot",
  PRODUCTION_BATCH: "Production batch",
  SERIAL: "Serial number",
};

export default function TraceabilityPage() {
  const [term, setTerm] = useState("");
  const [submitted, setSubmitted] = useState("");
  const path = `/traceability/search?q=${encodeURIComponent(submitted)}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<SearchHit[]>(path),
    enabled: submitted.length > 0,
  });
  const search = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(term.trim());
  };
  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader title="Traceability">
        <RecordLink href="/traceability/serials">Serial number search</RecordLink>
      </PageHeader>
      <form onSubmit={search} className="flex items-end gap-2">
        <div className="flex-1 space-y-1.5">
          <Label htmlFor="trace-search">Batch, serial, receipt, material or product</Label>
          <Input id="trace-search" value={term} onChange={(event) => setTerm(event.target.value)} />
        </div>
        <Button type="submit">Search</Button>
      </form>
      {submitted && !data && <QueryState error={error} />}
      {data?.length === 0 && <p className="text-sm text-muted-foreground">Nothing matches.</p>}
      <ul className="divide-y text-sm">
        {data?.map((hit) => (
          <li key={hit.serialNumber ?? hit.batchNumber} className="py-3">
            <RecordLink href={searchHitLink(hit)}>{hit.serialNumber ?? hit.batchNumber}</RecordLink>{" "}
            <span className="text-muted-foreground">{KINDS[hit.kind]}</span>
            <p>{hit.description}</p>
            <p className="text-muted-foreground">{hit.matchedOn}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}
