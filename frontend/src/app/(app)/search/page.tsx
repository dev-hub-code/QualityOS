"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { searchHitLink } from "@/lib/search";
import type {
  AuditSummary,
  CapaSummary,
  Customer,
  Dispatch,
  InspectionSummary,
  Instrument,
  Machine,
  Material,
  NcrSummary,
  Product,
  PurchaseOrderSummary,
  RecallSummary,
  ReceiptSummary,
  ReworkSummary,
  SearchHit,
  Supplier,
  WorkOrder,
} from "@/lib/types";

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
  const canSeeWorkOrders = usePermission("WORK_ORDER_VIEW");
  const canSeeMachines = usePermission("MANUFACTURING_VIEW");
  const canSeeInspections = usePermission("INSPECTION_VIEW");
  const canSeeAudits = usePermission("AUDIT_VIEW");
  const canSeeInstruments = usePermission("CALIBRATION_VIEW");
  const canSeeItems = usePermission("PRODUCT_VIEW");
  const canSeePurchasing = usePermission("PURCHASE_VIEW");
  const canSeeRecalls = usePermission("RECALL_VIEW");
  const nothingToSearch = ![
    canTrace,
    canSeeNcrs,
    canSeeCapas,
    canSeeSuppliers,
    canSeeWorkOrders,
    canSeeMachines,
    canSeeInspections,
    canSeeAudits,
    canSeeInstruments,
    canSeeItems,
    canSeePurchasing,
    canSeeRecalls,
  ].some(Boolean);
  const lower = term.toLowerCase();
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
          {canSeeWorkOrders && (
            <Kind<PageResponse<WorkOrder>>
              title="Work orders"
              path={`/manufacturing/work-orders?${page}`}
              paged
              toFound={(orders) =>
                orders.items.map((order) => ({
                  key: order.id,
                  href: `/manufacturing/work-orders/${order.id}`,
                  label: order.workOrderNumber,
                  description: `${order.productCode} – ${order.productName}`,
                  detail: <StatusBadge status={order.status} />,
                }))
              }
            />
          )}
          {canSeeWorkOrders && (
            <Kind<PageResponse<ReworkSummary>>
              title="Rework orders"
              path={`/manufacturing/rework-orders?${page}`}
              paged
              toFound={(reworks) =>
                reworks.items.map((rework) => ({
                  key: rework.id,
                  href: `/manufacturing/rework-orders/${rework.id}`,
                  label: rework.reworkNumber,
                  description: `${rework.workOrderNumber}${rework.productCode ? ` · ${rework.productCode}` : ""}`,
                  detail: <StatusBadge status={rework.status} />,
                }))
              }
            />
          )}
          {canSeeMachines && (
            <Kind<PageResponse<Machine>>
              title="Machines"
              path={`/manufacturing/machines?${page}`}
              paged
              toFound={(machines) =>
                machines.items.map((machine) => ({
                  key: machine.id,
                  href: `/manufacturing/machines/${machine.id}`,
                  label: machine.code,
                  description: machine.name,
                  detail: <StatusBadge status={machine.status} />,
                }))
              }
            />
          )}
          {canSeeInspections && (
            <Kind<PageResponse<InspectionSummary>>
              title="Inspections"
              path={`/inspections?${page}`}
              paged
              toFound={(inspections) =>
                inspections.items.map((inspection) => ({
                  key: inspection.id,
                  href: `/inspections/${inspection.id}`,
                  label: inspection.inspectionNumber,
                  description: `${inspection.materialCode} – ${inspection.materialName} · ${inspection.batchNumber}`,
                  detail: <StatusBadge status={inspection.status} />,
                }))
              }
            />
          )}
          {canSeeAudits && (
            <Kind<PageResponse<AuditSummary>>
              title="Audits"
              path={`/audits?${page}`}
              paged
              toFound={(audits) =>
                audits.items.map((audit) => ({
                  key: audit.id,
                  href: `/audits/${audit.id}`,
                  label: audit.auditNumber,
                  description: audit.title,
                  detail: <StatusBadge status={audit.status} />,
                }))
              }
            />
          )}
          {canSeeInstruments && (
            <Kind<PageResponse<Instrument>>
              title="Instruments"
              path={`/instruments?${page}`}
              paged
              toFound={(instruments) =>
                instruments.items.map((instrument) => ({
                  key: instrument.id,
                  href: `/calibration/${instrument.id}`,
                  label: instrument.instrumentCode,
                  description: instrument.name,
                  detail: instrument.serialNumber ? `Serial ${instrument.serialNumber}` : undefined,
                }))
              }
            />
          )}
          {canSeeItems && (
            <Kind<PageResponse<Product>>
              title="Products"
              path={`/products?${page}`}
              paged
              toFound={(products) =>
                products.items.map((product) => ({
                  key: product.id,
                  href: `/products/${product.id}`,
                  label: product.code,
                  description: product.name,
                }))
              }
            />
          )}
          {canSeeItems && (
            <Kind<PageResponse<Material>>
              title="Materials"
              path={`/materials?${page}`}
              paged
              toFound={(materials) =>
                materials.items.map((material) => ({
                  key: material.id,
                  href: `/materials/${material.id}`,
                  label: material.code,
                  description: material.name,
                  detail: material.grade ? `Grade ${material.grade}` : undefined,
                }))
              }
            />
          )}
          {canSeePurchasing && (
            <Kind<PageResponse<PurchaseOrderSummary>>
              title="Purchase orders"
              path={`/purchase-orders?${page}`}
              paged
              toFound={(orders) =>
                orders.items.map((order) => ({
                  key: order.id,
                  href: `/purchase-orders/${order.id}`,
                  label: order.poNumber,
                  description: order.supplierName,
                  detail: <StatusBadge status={order.status} />,
                }))
              }
            />
          )}
          {canSeePurchasing && (
            <Kind<PageResponse<ReceiptSummary>>
              title="Material receipts"
              path={`/material-receipts?${page}`}
              paged
              toFound={(receipts) =>
                receipts.items.map((receipt) => ({
                  key: receipt.id,
                  href: `/material-receipts/${receipt.id}`,
                  label: receipt.grnNumber,
                  description: `${receipt.supplierName}, received ${receipt.receivedOn}`,
                  detail: <StatusBadge status={receipt.status} />,
                }))
              }
            />
          )}
          {canTrace && (
            <Kind<PageResponse<Dispatch>>
              title="Dispatches"
              path={`/dispatches?${page}`}
              paged
              toFound={(dispatches) =>
                dispatches.items.map((dispatch) => ({
                  key: dispatch.id,
                  href: "/dispatches",
                  label: dispatch.dispatchNumber,
                  description: `${dispatch.customerName}, dispatched ${dispatch.dispatchedOn}`,
                  detail: dispatch.reference ? `Reference ${dispatch.reference}` : undefined,
                }))
              }
            />
          )}
          {canSeeRecalls && (
            <Kind<PageResponse<RecallSummary>>
              title="Recalls"
              path={`/recalls?${page}`}
              paged
              toFound={(recalls) =>
                recalls.items.map((recall) => ({
                  key: recall.id,
                  href: `/recalls/${recall.id}`,
                  label: recall.recallNumber,
                  description: recall.reason,
                  detail: <StatusBadge status={recall.status} />,
                }))
              }
            />
          )}
          {canTrace && (
            <Kind<Customer[]>
              title="Customers"
              path="/customers"
              toFound={(customers) =>
                customers
                  .filter((customer) =>
                    [customer.code, customer.name, customer.gstin ?? ""].some((value) =>
                      value.toLowerCase().includes(lower),
                    ),
                  )
                  .slice(0, PER_KIND)
                  .map((customer) => ({
                    key: customer.id,
                    href: "/customers",
                    label: customer.code,
                    description: customer.name,
                    detail: customer.gstin ? `GSTIN ${customer.gstin}` : undefined,
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
