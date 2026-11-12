"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { searchHitLink } from "@/lib/search";
import type { CapaSummary, NcrSummary, SearchHit, Supplier } from "@/lib/types";

/** How many of each kind a search shows. A wider list is on that kind's own page, searched there. */
const PER_KIND = 10;

interface Found {
  key: string;
  href: string;
  label: string;
  description: ReactNode;
  detail?: ReactNode;
}

/** One kind of record. Shown only to people allowed to see that kind, so a search never asks for what it cannot show. */
function Kind<T>({
  title,
  path,
  toFound,
  paged = false,
}: {
  title: string;
  path: string;
  toFound: (data: T) => Found[];
  /** Whether the list stops at {@link PER_KIND}. */
  paged?: boolean;
}) {
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<T>(path) });
  const found = data ? toFound(data) : [];
  return (
    <Section title={title}>
      {!data && <QueryState error={error} />}
      {data && found.length === 0 && <p className="text-sm text-muted-foreground">Nothing matches.</p>}
      <ul className="divide-y text-sm">
        {found.map((item) => (
          <li key={item.key} className="py-3">
            <RecordLink href={item.href}>{item.label}</RecordLink>
            <p>{item.description}</p>
            {item.detail && <p className="text-muted-foreground">{item.detail}</p>}
          </li>
        ))}
      </ul>
      {paged && found.length >= PER_KIND && (
        <p className="pt-2 text-sm text-muted-foreground">Showing the first {PER_KIND}. Narrow the search to see others.</p>
      )}
    </Section>
  );
}

function Results() {
  const term = useSearchParams().get("q") ?? "";
  const q = encodeURIComponent(term);
  const page = `q=${q}&size=${PER_KIND}`;
  const canTrace = usePermission("TRACEABILITY_VIEW");
  const canSeeNcrs = usePermission("NCR_VIEW");
  const canSeeCapas = usePermission("CAPA_VIEW");
  const canSeeSuppliers = usePermission("SUPPLIER_VIEW");
  const nothingToSearch = !canTrace && !canSeeNcrs && !canSeeCapas && !canSeeSuppliers;
  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader title="Search" subtitle={`Results for “${term}”`} />
      {term.length > 0 && (
        <>
          {canTrace && (
            <Kind<SearchHit[]>
              title="Lots, batches and serial numbers"
              path={`/traceability/search?q=${q}`}
              toFound={(hits) =>
                hits.map((hit) => ({
                  key: hit.serialNumber ?? hit.batchNumber,
                  href: searchHitLink(hit),
                  label: hit.serialNumber ?? hit.batchNumber,
                  description: hit.description,
                  detail: hit.matchedOn,
                }))
              }
            />
          )}
          {canSeeNcrs && (
            <Kind<PageResponse<NcrSummary>>
              title="Non-conformances"
              path={`/ncrs?${page}`}
              paged
              toFound={(ncrs) =>
                ncrs.items.map((ncr) => ({
                  key: ncr.id,
                  href: `/ncrs/${ncr.id}`,
                  label: ncr.ncrNumber,
                  description: ncr.title,
                  detail: <StatusBadge status={ncr.status} />,
                }))
              }
            />
          )}
          {canSeeCapas && (
            <Kind<PageResponse<CapaSummary>>
              title="CAPAs"
              path={`/capas?${page}`}
              paged
              toFound={(capas) =>
                capas.items.map((capa) => ({
                  key: capa.id,
                  href: `/capas/${capa.id}`,
                  label: capa.capaNumber,
                  description: capa.title,
                  detail: <StatusBadge status={capa.status} />,
                }))
              }
            />
          )}
          {canSeeSuppliers && (
            <Kind<PageResponse<Supplier>>
              title="Suppliers"
              path={`/suppliers?${page}`}
              paged
              toFound={(suppliers) =>
                suppliers.items.map((supplier) => ({
                  key: supplier.id,
                  href: `/suppliers/${supplier.id}`,
                  label: supplier.code,
                  description: supplier.name,
                  detail: supplier.gstin ? `GSTIN ${supplier.gstin}` : undefined,
                }))
              }
            />
          )}
          {nothingToSearch && <p className="text-sm text-muted-foreground">There is nothing here you can search.</p>}
        </>
      )}
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
