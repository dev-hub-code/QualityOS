"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { machineFields } from "@/components/manufacturing/work-center-fields";
import { Details, PageHeader, QueryState, Section, StatusBadge, useRefresh, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Machine, MachineStatusEvent } from "@/lib/types";

interface MachineDetail {
  machine: Machine;
  history: MachineStatusEvent[];
}

const label = (status: string) => status.charAt(0) + status.slice(1).toLowerCase();

export default function MachinePage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/machines/${id}`;
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const workCenters = useWorkCenters();
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<"edit" | "status" | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<MachineDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { machine, history } = data;
  return (
    <div className="space-y-6">
      <PageHeader
        title={machine.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={machine.status} />
            {machine.code}
          </span>
        }
      >
        {canManage && (
          <>
            <Button variant="outline" onClick={() => setDialog("edit")}>
              Edit
            </Button>
            <Button onClick={() => setDialog("status")}>Change status</Button>
          </>
        )}
      </PageHeader>
      <Section title="Details">
        <Details
          items={[
            [
              "Work centre",
              <Link key="centre" className="text-primary hover:underline" href={`/manufacturing/work-centers/${machine.workCenterId}`}>
                {machine.workCenterCode}
              </Link>,
            ],
            ["Manufacturer", machine.manufacturer ?? "–"],
            ["Model", machine.model ?? "–"],
            ["Serial number", machine.serialNumber ?? "–"],
            ["Status since", new Date(machine.statusChangedAt).toLocaleString()],
            ["Reason", machine.statusReason ?? "–"],
          ]}
        />
      </Section>
      <Section title="Status history">
        <ul className="divide-y text-sm">
          {history.map((event) => (
            <li key={`${event.changedAt}-${event.toStatus}`} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span className="flex items-center gap-2">
                <StatusBadge status={event.toStatus} />
                {event.reason}
              </span>
              <span className="text-muted-foreground">
                {event.changedByName ?? "Former member"} · {new Date(event.changedAt).toLocaleString()}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {dialog === "edit" && (
        <RecordDialog
          title="Edit machine"
          fields={machineFields(workCenters)}
          record={machine}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, values).then(refresh)}
        />
      )}
      {dialog === "status" && (
        <RecordDialog
          title="Change status"
          fields={[
            {
              name: "status",
              label: "New status",
              type: "select",
              required: true,
              options: machine.nextStatuses.map((status) => ({ value: status, label: label(status) })),
            },
            { name: "reason", label: "Reason (needed for maintenance, breakdown and inactive)", type: "textarea" },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/status`, values).then(refresh)}
        />
      )}
    </div>
  );
}
