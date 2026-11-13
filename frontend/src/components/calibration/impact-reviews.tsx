"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { RecordLink, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { ImpactReview, ImpactReviewGroup } from "@/lib/types";

const DECISION_FIELDS: FieldDef[] = [
  {
    name: "decision",
    label: "Decision",
    type: "select",
    required: true,
    options: [
      { value: "RESULT_STANDS", label: "Result stands: the measurement is still trusted" },
      { value: "REINSPECT", label: "Reinspect: hold the goods until a new inspection releases them" },
    ],
  },
  { name: "note", label: "Why", type: "textarea", required: true },
];

/** Every inspection in doubt after a failed calibration, and the decision each one needs. */
export function ImpactReviews({ path, canManage }: { path: string; canManage: boolean }) {
  const refresh = useRefresh();
  const [deciding, setDeciding] = useState<ImpactReview | null>(null);
  const { data: groups = [] } = useQuery({
    queryKey: [path, "impact-reviews"],
    queryFn: () => api.get<ImpactReviewGroup[]>(`${path}/impact-reviews`),
  });
  if (groups.length === 0) {
    return <p className="text-sm text-muted-foreground">No failed calibration has put an inspection in doubt.</p>;
  }
  return (
    <div className="space-y-4">
      {groups.map((group) => (
        <div key={group.calibrationId} className="space-y-2">
          <p className="text-sm">
            Failed on {group.calibratedOn}
            {group.ncrId && (
              <>
                {" · "}
                <RecordLink href={`/ncrs/${group.ncrId}`}>{group.ncrNumber}</RecordLink>
                {group.ncrStatus && <span className="ml-2"><StatusBadge status={group.ncrStatus} /></span>}
              </>
            )}
            {" · "}
            {group.pending === 0 ? "all decided" : `${group.pending} waiting for a decision`}
          </p>
          <ul className="divide-y text-sm">
            {group.reviews.map((review) => (
              <li key={review.id} className="flex flex-wrap items-center gap-2 py-2">
                <RecordLink href={`/inspections/${review.inspectionId}`}>{review.inspectionNumber}</RecordLink>
                <span className="text-muted-foreground">used {review.usedOn}</span>
                {review.decision ? (
                  <>
                    <StatusBadge status={review.decision} />
                    <span className="text-muted-foreground">
                      {review.note} – {review.decidedByName}
                    </span>
                  </>
                ) : canManage ? (
                  <Button size="sm" variant="outline" onClick={() => setDeciding(review)}>
                    Decide
                  </Button>
                ) : (
                  <StatusBadge status="PENDING" />
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {deciding && (
        <RecordDialog
          title={`Decide on ${deciding.inspectionNumber}`}
          fields={DECISION_FIELDS}
          onClose={() => setDeciding(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/impact-reviews/${deciding.id}/decision`, values);
            refresh();
          }}
        />
      )}
    </div>
  );
}
