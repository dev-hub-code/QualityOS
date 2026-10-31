"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PageHeader, QueryState, RecordLink } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { AuditReport } from "@/lib/types";

const CELL = "border p-1.5 align-top break-words";

export default function AuditReportPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/audits/${id}/report`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<AuditReport>(path) });
  if (!data) return <QueryState error={error} />;
  const { audit } = data;
  return (
    <div className="space-y-5">
      <style>{`@media print { @page { size: A4 landscape; margin: 14mm 10mm; @bottom-right { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; } } }`}</style>
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <RecordLink href={`/audits/${id}`}>Back to audit</RecordLink>
        <Button variant="outline" onClick={() => window.print()}>
          Print
        </Button>
        <a className="text-sm underline" href={`/api/v1/audits/${id}/report.pdf`}>
          Download PDF
        </a>
      </div>
      <PageHeader title={`Quality audit report: ${audit.auditNumber}`} subtitle={data.organizationName} />
      <p className="text-sm">
        {audit.title} · {audit.facilityName} · Lead auditor {audit.leadAuditorName} · Status {audit.status} · Generated{" "}
        {data.generatedLabel}
      </p>
      <section className="space-y-1 text-sm">
        <h2 className="border-b text-base font-semibold">Scope</h2>
        <p className="whitespace-pre-wrap">{audit.scope}</p>
        {audit.summary && <p className="whitespace-pre-wrap">{audit.summary}</p>}
        <p>
          {audit.review.answered} of {audit.review.items} questions answered: {audit.review.conforming} conforming,{" "}
          {audit.review.nonconforming} nonconforming, {audit.review.notApplicable} not applicable.
        </p>
      </section>
      <section className="space-y-2">
        <h2 className="border-b text-base font-semibold">Findings</h2>
        {audit.findings.length === 0 && <p className="text-sm">No findings.</p>}
        <ol className="space-y-2 text-sm">
          {audit.findings.map((finding) => (
            <li key={finding.id} className="break-inside-avoid">
              {finding.number}. {finding.grade.replaceAll("_", " ").toLowerCase()}: {finding.statement}. Evidence:{" "}
              {finding.evidence}
              {finding.ncr && ` (${finding.ncr.ncrNumber}, ${finding.ncr.status.toLowerCase()})`}
            </li>
          ))}
        </ol>
      </section>
      <section className="space-y-2">
        <h2 className="border-b text-base font-semibold">Checklist</h2>
        <div className="overflow-x-auto print:overflow-visible">
          <table className="w-full border-collapse text-sm print:text-xs">
            <thead className="print:table-header-group">
              <tr>
                {["No.", "Question", "Clause", "Result", "Note"].map((heading) => (
                  <th key={heading} className={`${CELL} bg-muted text-left font-medium`}>
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {audit.items.map((item) => (
                <tr key={item.id} className="break-inside-avoid">
                  <td className={CELL}>{item.position}</td>
                  <td className={CELL}>{item.question}</td>
                  <td className={CELL}>{item.clauseReference}</td>
                  <td className={CELL}>{item.result?.replaceAll("_", " ").toLowerCase()}</td>
                  <td className={CELL}>{item.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <p className="text-xs text-muted-foreground">
        This report records an organisation&apos;s own quality audit. It is not a certificate of compliance with any
        standard.
      </p>
    </div>
  );
}
