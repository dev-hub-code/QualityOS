"use client";

import { NcrStatusBadge } from "@/components/records/ncr-status-badge";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  RecordLink,
  StatusBadge,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import { usePermission } from "@/hooks/use-me";
import type {
  ImpactReview,
  ImpactReviewGroup,
  Inspection,
  InspectionReach,
} from "@/lib/types";

const DECISION_FIELDS: FieldDef[] = [
  {
    name: "decision",
    label: "Decision",
    type: "select",
    required: true,
    options: [
      {
        value: "RESULT_STANDS",
        label: "Result stands: the measurement is still trusted",
      },
      {
        value: "REINSPECT",
        label: "Reinspect: hold the goods until a new inspection releases them",
      },
    ],
  },
  { name: "note", label: "Why", type: "textarea", required: true },
];

/**
 * After Reinspect: the inspection started to check the goods again, and where the goods already went. What was used or
 * shipped before the hold is not held by it, so a recall report is offered for each batch it reached.
 */
function ReinspectFollowUp({ inspectionId }: { inspectionId: string }) {
  const canSeeInspections = usePermission("INSPECTION_VIEW");
  const canTrace = usePermission("TRACEABILITY_VIEW");
  const { data: inspection } = useQuery({
    queryKey: [`/inspections/${inspectionId}`],
    queryFn: () => api.get<Inspection>(`/inspections/${inspectionId}`),
    enabled: canSeeInspections,
  });
  const { data: reach } = useQuery({
    queryKey: [`/traceability/inspections/${inspectionId}/reach`],
    queryFn: () =>
      api.get<InspectionReach>(
        `/traceability/inspections/${inspectionId}/reach`,
      ),
    enabled: canTrace,
  });
  return (
    <div className="w-full space-y-1 border-l-2 pl-3 text-xs text-muted-foreground">
      {inspection &&
        (inspection.reinspectedBy ? (
          <p>
            Checked again by{" "}
            <RecordLink href={`/inspections/${inspection.reinspectedBy.id}`}>
              {inspection.reinspectedBy.inspectionNumber}
            </RecordLink>{" "}
            ({inspection.reinspectedBy.status.toLowerCase().replace("_", " ")})
          </p>
        ) : (
          <p>
            The new inspection could not start on its own. Start it from{" "}
            <RecordLink
              href={
                inspection.productionBatchId
                  ? `/production-batches/${inspection.productionBatchId}`
                  : `/traceability/${encodeURIComponent(inspection.batchNumber)}`
              }
            >
              {inspection.batchNumber}
            </RecordLink>
            : the goods are held until then.
          </p>
        ))}
      {reach && (reach.usedIn.length > 0 || reach.customers.length > 0) && (
        <>
          <p>
            Not held, because already used or shipped:
            {reach.usedIn.length > 0 && (
              <> went into {reach.usedIn.join(", ")}</>
            )}
            {reach.usedIn.length > 0 && reach.customers.length > 0 && ";"}
            {reach.customers.length > 0 && (
              <> held by {reach.customers.join(", ")}</>
            )}
            .
          </p>
          <p className="flex flex-wrap gap-2">
            {reach.recallable.map((batch) => (
              <RecordLink
                key={batch}
                href={`/traceability/${encodeURIComponent(batch)}/recall`}
              >
                Recall report for {batch}
              </RecordLink>
            ))}
          </p>
        </>
      )}
    </div>
  );
}

/** Every inspection in doubt after a failed calibration, and the decision each one needs. */
export function ImpactReviews({
  path,
  canManage,
}: {
  path: string;
  canManage: boolean;
}) {
  const refresh = useRefresh();
  const [deciding, setDeciding] = useState<ImpactReview | null>(null);
  const { data: groups = [] } = useQuery({
    queryKey: [path, "impact-reviews"],
    queryFn: () => api.get<ImpactReviewGroup[]>(`${path}/impact-reviews`),
  });
  if (groups.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No failed calibration has put a measurement in doubt.
      </p>
    );
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
                <RecordLink href={`/ncrs/${group.ncrId}`}>
                  {group.ncrNumber}
                </RecordLink>
                {group.ncrStatus && (
                  <span className="ml-2">
                    <NcrStatusBadge status={group.ncrStatus} />
                  </span>
                )}
              </>
            )}
            {group.reviews.length > 0 && (
              <>
                {" · "}
                {group.pending === 0
                  ? "all decided"
                  : `${group.pending} waiting for a decision`}
              </>
            )}
          </p>
          {group.otherUses.length > 0 && (
            <div className="text-sm">
              <p className="text-muted-foreground">
                Shop-floor measurements in doubt, covered by the NCR:
              </p>
              <ul className="divide-y">
                {group.otherUses.map((use) => (
                  <li
                    key={use.link}
                    className="flex flex-wrap items-center gap-2 py-2"
                  >
                    <RecordLink href={use.link}>{use.label}</RecordLink>
                    <span className="text-muted-foreground">
                      used {use.usedOn}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <ul className="divide-y text-sm">
            {group.reviews.map((review) => (
              <li
                key={review.id}
                className="flex flex-wrap items-center gap-2 py-2"
              >
                <RecordLink href={`/inspections/${review.inspectionId}`}>
                  {review.inspectionNumber}
                </RecordLink>
                <span className="text-muted-foreground">
                  used {review.usedOn}
                  {review.parameters.length > 0
                    ? `, measured ${review.parameters.join(", ")}`
                    : ", measurements not named"}
                </span>
                {review.decision ? (
                  <>
                    <StatusBadge status={review.decision} />
                    <span className="text-muted-foreground">
                      {review.note} – {review.decidedByName}
                    </span>
                    {review.decision === "REINSPECT" && (
                      <ReinspectFollowUp inspectionId={review.inspectionId} />
                    )}
                  </>
                ) : canManage ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setDeciding(review)}
                  >
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
            await api.post(
              `${path}/impact-reviews/${deciding.id}/decision`,
              values,
            );
            refresh();
          }}
        />
      )}
    </div>
  );
}
