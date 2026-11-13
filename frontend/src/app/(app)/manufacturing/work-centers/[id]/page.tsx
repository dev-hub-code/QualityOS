"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { machineFields, workCenterFields, workCenterRecord } from "@/components/manufacturing/work-center-fields";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useAction,
  useFacilities,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FacilityDetail, Machine, WorkCenter } from "@/lib/types";

interface WorkCenterDetail {
  workCenter: WorkCenter;
  machines: Machine[];
}

const rate = (value: string | null) => (value === null || value === undefined ? "Not set" : String(Number(value)));

export default function WorkCenterPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/work-centers/${id}`;
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const facilities = useFacilities();
  const refresh = useRefresh();
  const router = useRouter();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<"edit" | "machine" | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<WorkCenterDetail>(path) });
  const facilityId = data?.workCenter.facilityId;
  const { data: facility } = useQuery({
    queryKey: [`/facilities/${facilityId}`],
    queryFn: () => api.get<FacilityDetail>(`/facilities/${facilityId}`),
    enabled: Boolean(facilityId),
  });
  if (!data) return <QueryState error={error} />;

  const { workCenter, machines } = data;
  const lines = (facility?.productionLines ?? []).map((line) => ({ value: line.id, label: `${line.code} – ${line.name}` }));
  const active = !workCenter.archived;
  return (
    <div className="space-y-6">
      <PageHeader
        title={workCenter.name}
        subtitle={`${workCenter.code} · ${workCenter.facilityCode ?? ""}${workCenter.archived ? " · archived" : ""}`}
      >
        {canManage && active && (
          <>
            <Button variant="outline" onClick={() => setDialog("edit")}>
              Edit
            </Button>
            <Button onClick={() => setDialog("machine")}>Add machine</Button>
            <Button
              variant="outline"
              disabled={pending}
              onClick={() =>
                run(async () => {
                  await api.delete(path);
                  router.push("/manufacturing/work-centers");
                })
              }
            >
              Archive
            </Button>
          </>
        )}
      </PageHeader>
      <FormError message={message} />
      <Section title="Details">
        <Details
          items={[
            ["Facility", workCenter.facilityCode],
            ["Production line", workCenter.productionLineCode ?? "–"],
            ["Description", workCenter.description ?? "–"],
            [
              "Capacity",
              machines.some((machine) => machine.status !== "INACTIVE")
                ? "One operation per active machine"
                : `${workCenter.capacityUnits} at once`,
            ],
            ["Labour per hour", rate(workCenter.labourRate)],
            ["Machine per hour", rate(workCenter.machineRate)],
            ["Overhead per hour", rate(workCenter.overheadRate)],
          ]}
        />
      </Section>
      <Section title="Machines">
        {machines.length === 0 && <p className="text-sm text-muted-foreground">No machines yet.</p>}
        <ul className="divide-y text-sm">
          {machines.map((machine) => (
            <li key={machine.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <Link className="font-medium text-primary hover:underline" href={`/manufacturing/machines/${machine.id}`}>
                {machine.code} – {machine.name}
              </Link>
              <StatusBadge status={machine.status} />
            </li>
          ))}
        </ul>
      </Section>
      {dialog === "edit" && (
        <RecordDialog
          title="Edit work centre"
          fields={workCenterFields(facilities, lines).map((field) =>
            // A work centre stays in its facility.
            field.name === "facilityId" ? { ...field, options: facilities.filter((f) => f.value === workCenter.facilityId) } : field,
          )}
          record={workCenterRecord(workCenter)}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, values).then(refresh)}
        />
      )}
      {dialog === "machine" && (
        <RecordDialog
          title="Add machine"
          fields={machineFields([{ value: workCenter.id, label: `${workCenter.code} – ${workCenter.name}` }])}
          record={{ workCenterId: workCenter.id }}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post("/manufacturing/machines", values).then(refresh)}
        />
      )}
    </div>
  );
}
