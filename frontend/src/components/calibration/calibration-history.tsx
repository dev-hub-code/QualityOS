"use client";

import { useState } from "react";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { RecordLink, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import type { CalibrationRecord, InstrumentUse } from "@/lib/types";

export function CalibrationHistory({ records, canManage }: { records: CalibrationRecord[]; canManage: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  if (records.length === 0) return <p className="text-sm text-muted-foreground">Not calibrated here yet.</p>;
  return (
    <ul className="divide-y text-sm">
      {records.map((record) => (
        <li key={record.id} className="space-y-1 py-2">
          <div className="flex flex-wrap items-center gap-2">
            {record.calibratedOn}
            <StatusBadge status={record.result} />
            {record.performedBy}
            {record.certificateNumber && ` · Certificate ${record.certificateNumber}`}
            <Button size="sm" variant="ghost" onClick={() => setOpen(open === record.id ? null : record.id)}>
              Certificates
            </Button>
          </div>
          {record.notes && <p className="text-muted-foreground">{record.notes}</p>}
          {open === record.id && (
            <AttachmentPanel entityType="CALIBRATION_RECORD" entityId={record.id} canManage={canManage} />
          )}
        </li>
      ))}
    </ul>
  );
}

export function InstrumentUsage({ uses }: { uses: InstrumentUse[] }) {
  if (uses.length === 0) return <p className="text-sm text-muted-foreground">No inspection has recorded this instrument.</p>;
  const suspect = uses.filter((use) => use.suspect).length;
  return (
    <>
      {suspect > 0 && (
        <p className="mb-2 text-sm text-destructive">
          {suspect} inspection(s) used this instrument since its last good calibration. Review them.
        </p>
      )}
      <ul className="divide-y text-sm">
        {uses.map((use) => (
          <li key={use.inspectionId} className="flex flex-wrap items-center gap-2 py-2">
            <RecordLink href={`/inspections/${use.inspectionId}`}>{use.inspectionNumber}</RecordLink>
            {use.usedOn}
            {use.suspect && <StatusBadge status="HOLD" />}
          </li>
        ))}
      </ul>
    </>
  );
}
