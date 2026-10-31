"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PageHeader, QueryState, RecordLink } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { RecallReport } from "@/lib/types";

const CELL = "border p-1.5 align-top";
const HEAD = `${CELL} bg-muted text-left font-medium`;

function ReportTable({ headings, rows }: { headings: string[]; rows: React.ReactNode[][] }) {
  return (
    <div className="overflow-x-auto print:overflow-visible">
      <table className="w-full border-collapse text-sm print:text-xs">
        <thead className="print:table-header-group">
          <tr>
            {headings.map((heading) => (
              <th key={heading} className={HEAD}>
                {heading}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index} className="break-inside-avoid">
              {row.map((cell, column) => (
                <td key={column} className={`${CELL} break-words`}>
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Part({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="border-b text-base font-semibold break-after-avoid">{title}</h2>
      {children}
    </section>
  );
}

export default function RecallReportPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/recalls/${id}/report`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<RecallReport>(path) });
  if (!data) return <QueryState error={error} />;
  const { recall } = data;
  return (
    <div className="space-y-5">
      <style>{`@media print { @page { size: A4 landscape; margin: 14mm 10mm; @bottom-right { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; } } }`}</style>
      <div className="flex flex-wrap gap-2 print:hidden">
        <RecordLink href={`/recalls/${id}`}>Back to recall</RecordLink>
        <Button variant="outline" onClick={() => window.print()}>
          Print
        </Button>
        <a className="text-sm underline" href={`/api/v1/recalls/${id}/report.pdf`}>
          Download PDF
        </a>
      </div>
      <PageHeader title={`Product recall report: ${recall.recallNumber}`} subtitle={data.organizationName} />
      <p className="text-sm text-muted-foreground">
        {[data.organizationLegalName, data.organizationAddress, data.organizationGstin && `GSTIN ${data.organizationGstin}`]
          .filter(Boolean)
          .join(", ")}
      </p>
      <p className="text-sm">
        Recall date {recall.recallDate} · Status {recall.status} · Generated {data.generatedLabel}
      </p>
      <Part title="Reason for recall">
        <p className="text-sm whitespace-pre-wrap">{recall.reason}</p>
      </Part>
      <Part title="Action required">
        <p className="text-sm whitespace-pre-wrap">{recall.instructions}</p>
      </Part>
      <Part title="Quantities">
        <p className="text-sm">
          {recall.review.customers} {recall.review.customers === 1 ? "customer" : "customers"}. {formatQuantity(recall.review.dispatchedQuantity)} dispatched,{" "}
          {formatQuantity(recall.review.returnedQuantity)} returned, {formatQuantity(recall.review.outstandingQuantity)}{" "}
          outstanding.
        </p>
      </Part>
      <Part title="Affected products and batches">
        <ReportTable
          headings={["Product code", "Product", "Batch", "Batch status"]}
          rows={data.products.map((product) => [product.code, product.name, product.batchNumber, product.status])}
        />
      </Part>
      <Part title="Customers">
        <ReportTable
          headings={["Customer", "Contact", "Status", "Attempts", "Acknowledged", "Dispatched", "Returned", "Outstanding"]}
          rows={data.customers.map((customer) => [
            `${customer.name} (${customer.code})${customer.exceptionReason ? `. Exception: ${customer.exceptionReason}` : ""}`,
            [customer.contactPerson, customer.phone, customer.email, customer.address].filter(Boolean).join(", "),
            customer.status.replaceAll("_", " ").toLowerCase(),
            customer.attempts,
            customer.acknowledgedOn,
            formatQuantity(customer.quantity),
            formatQuantity(customer.returnedQuantity),
            formatQuantity(customer.outstandingQuantity),
          ])}
        />
      </Part>
      <Part title="Dispatch references">
        <ReportTable
          headings={["Dispatch", "Customer", "Dispatched on", "Shipped on", "Quantity", "Returned", "Outstanding"]}
          rows={data.dispatches.map((line) => [
            line.dispatchNumber,
            line.customerName,
            line.dispatchedOn,
            line.shippedOn,
            formatQuantity(line.quantity),
            formatQuantity(line.returnedQuantity),
            formatQuantity(line.outstandingQuantity),
          ])}
        />
      </Part>
      {data.serialNumbers.length > 0 && (
        <Part title={`Affected serial numbers (${data.serialNumbers.length})`}>
          <p className="text-xs break-words">{data.serialNumbers.join(", ")}</p>
        </Part>
      )}
      {data.inspections.length > 0 && (
        <Part title="Inspection history">
          <ReportTable
            headings={["Inspection", "Type", "Batch", "Status", "Result", "Disposition"]}
            rows={data.inspections.map((row) => [row.inspectionNumber, row.inspectionType, row.batchNumber, row.status, row.finalResult, row.disposition])}
          />
        </Part>
      )}
      {data.ncrs.length > 0 && (
        <Part title="Defect history">
          <ReportTable
            headings={["NCR", "Title", "Severity", "Status"]}
            rows={data.ncrs.map((row) => [row.ncrNumber, row.title, row.severity, row.status])}
          />
        </Part>
      )}
      {data.history.length > 0 && (
        <Part title="Contact history">
          <ReportTable
            headings={["When", "Customer", "Kind", "Outcome", "Response", "Recorded by"]}
            rows={data.history.map((entry) => [
              entry.on,
              entry.customerName,
              entry.kindLabel,
              entry.outcomeLabel,
              entry.customerResponse,
              entry.recordedBy,
            ])}
          />
        </Part>
      )}
    </div>
  );
}
