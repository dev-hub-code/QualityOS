"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { Details, PageHeader, QueryState, Section, StatusBadge, useUnits } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { requirement, resultValue } from "@/lib/inspection";
import { formatQuantity } from "@/lib/record-form";
import type { Inspection } from "@/lib/types";

/** Built from the specification frozen into the inspection, so that it still reads the same in years to come. */
export default function InspectionReportPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/inspections/${id}`;
  const units = useUnits();
  const { data: inspection, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Inspection>(path) });
  if (!inspection) return <QueryState error={error} />;

  return (
    <div className="space-y-6">
      <PageHeader title={`Inspection report ${inspection.inspectionNumber}`}>
        <Button className="print:hidden" onClick={() => window.print()}>
          Print or save as PDF
        </Button>
      </PageHeader>
      <Section title="Summary">
        <Details
          items={[
            ["Material", `${inspection.materialCode} – ${inspection.materialName}`],
            ["Lot", inspection.batchNumber],
            ["Supplier", inspection.supplierName],
            ["Quantity", formatQuantity(inspection.quantity)],
            ["Samples", inspection.sampleSize],
            ["Template", `${inspection.specification.templateName} v${inspection.specification.versionNumber}`],
            ["Result", inspection.finalResult ?? inspection.evaluatedResult ?? "Not yet evaluated"],
            ["Disposition", inspection.disposition],
            ["Status", <StatusBadge key="status" status={inspection.status} />],
          ]}
        />
      </Section>
      <Section title="Measurements">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Parameter</TableHead>
              <TableHead>Requirement</TableHead>
              <TableHead>Sample</TableHead>
              <TableHead>Value</TableHead>
              <TableHead>Evaluation</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {inspection.results.map((result) => {
              const parameter = inspection.specification.parameters.find((p) => p.code === result.parameterCode);
              return (
                <TableRow key={`${result.parameterCode}-${result.sampleNumber}`}>
                  <TableCell>{parameter?.name ?? result.parameterCode}</TableCell>
                  <TableCell>
                    {parameter && requirement(parameter)} {units.label(parameter?.uomId ?? null)}
                  </TableCell>
                  <TableCell>{result.sampleNumber}</TableCell>
                  <TableCell>{resultValue(result)}</TableCell>
                  <TableCell>
                    <StatusBadge status={result.evaluation} />
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Section>
      <Section title="Decisions">
        <ul className="divide-y text-sm">
          {inspection.decisions.map((decision) => (
            <li key={decision.decidedAt + decision.decision} className="flex justify-between gap-3 py-2">
              <span>
                {decision.decision.replaceAll("_", " ").toLowerCase()}
                {decision.note && ` – ${decision.note}`}
              </span>
              <span className="text-muted-foreground">{new Date(decision.decidedAt).toLocaleString()}</span>
            </li>
          ))}
        </ul>
      </Section>
    </div>
  );
}
