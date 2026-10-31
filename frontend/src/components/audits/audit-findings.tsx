"use client";

import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { RecordLink, StatusBadge, useAction } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { AuditFinding } from "@/lib/types";

export function AuditFindings({
  auditId,
  findings,
  canRaise,
  canAttach,
}: {
  auditId: string;
  findings: AuditFinding[];
  canRaise: boolean;
  canAttach: boolean;
}) {
  const { run, message } = useAction();
  const [evidence, setEvidence] = useState<string | null>(null);
  if (findings.length === 0) return <p className="text-sm text-muted-foreground">No findings.</p>;
  return (
    <>
      <FormError message={message} />
      <ul className="divide-y text-sm">
        {findings.map((finding) => (
          <li key={finding.id} className="space-y-1 py-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">Finding {finding.number}</span>
              <StatusBadge status={finding.grade} />
              {finding.itemPosition && <span className="text-muted-foreground">Question {finding.itemPosition}</span>}
            </div>
            <p className="break-words">{finding.statement}</p>
            <p className="break-words text-muted-foreground">Evidence: {finding.evidence}</p>
            {finding.ncr && (
              <p className="flex flex-wrap items-center gap-2">
                <RecordLink href={`/ncrs/${finding.ncr.id}`}>{finding.ncr.ncrNumber}</RecordLink>
                <StatusBadge status={finding.ncr.status} />
                {finding.ncr.capas.map((capa) => (
                  <RecordLink key={capa.id} href={`/capas/${capa.id}`}>
                    {capa.capaNumber}
                  </RecordLink>
                ))}
              </p>
            )}
            <div className="flex flex-wrap gap-2">
              {canRaise && !finding.ncr && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => run(() => api.post(`/audits/${auditId}/findings/${finding.id}/ncr`))}
                >
                  Raise NCR
                </Button>
              )}
              <Button size="sm" variant="ghost" onClick={() => setEvidence(evidence === finding.id ? null : finding.id)}>
                Attachments
              </Button>
            </div>
            {evidence === finding.id && (
              <AttachmentPanel entityType="AUDIT_FINDING" entityId={finding.id} canManage={canAttach} />
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
