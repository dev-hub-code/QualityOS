"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { CalibrationHistory, InstrumentUsage } from "@/components/calibration/calibration-history";
import { instrumentFields } from "@/components/calibration/instrument-fields";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useAssignees,
  useFacilities,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { CalibrationRecord, Instrument, InstrumentUse } from "@/lib/types";

const CALIBRATION_FIELDS: FieldDef[] = [
  { name: "calibratedOn", label: "Calibrated on", type: "date", required: true },
  {
    name: "result",
    label: "Result",
    type: "select",
    required: true,
    options: [
      { value: "PASS", label: "Pass" },
      { value: "ADJUSTED", label: "Pass after adjustment" },
      { value: "FAIL", label: "Fail" },
    ],
  },
  { name: "performedBy", label: "Performed by", required: true },
  { name: "certificateNumber", label: "Certificate number" },
  { name: "notes", label: "Notes", type: "textarea" },
];
const STATUS_FIELDS: FieldDef[] = [
  {
    name: "status",
    label: "Status",
    type: "select",
    required: true,
    options: [
      { value: "ACTIVE", label: "Active" },
      { value: "OUT_OF_SERVICE", label: "Out of service" },
      { value: "RETIRED", label: "Retired" },
    ],
  },
  { name: "reason", label: "Reason", type: "textarea", required: true },
];

export default function InstrumentPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/instruments/${id}`;
  const canManage = usePermission("CALIBRATION_MANAGE");
  const facilities = useFacilities();
  const assignees = useAssignees();
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<"calibrate" | "edit" | "status" | null>(null);
  const { data: instrument, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Instrument>(path) });
  const { data: records = [] } = useQuery({
    queryKey: [path, "calibrations"],
    queryFn: () => api.get<CalibrationRecord[]>(`${path}/calibrations`),
  });
  const { data: uses = [] } = useQuery({
    queryKey: [path, "usage"],
    queryFn: () => api.get<InstrumentUse[]>(`${path}/usage`),
  });
  if (!instrument) return <QueryState error={error} />;
  const retired = instrument.status === "RETIRED";
  const close = () => setDialog(null);
  const facility = facilities.find((option) => option.value === instrument.facilityId)?.label;
  return (
    <div className="space-y-6">
      <div className="print:hidden">
        <PageHeader title={instrument.instrumentCode} subtitle={instrument.name}>
          <StatusBadge status={instrument.status} />
          <StatusBadge status={instrument.dueState === "OK" ? "IN_DATE" : instrument.dueState} />
          {canManage && !retired && (
            <>
              <Button onClick={() => setDialog("calibrate")}>Record calibration</Button>
              <Button variant="outline" onClick={() => setDialog("edit")}>
                Edit
              </Button>
              <Button variant="outline" onClick={() => setDialog("status")}>
                Change status
              </Button>
            </>
          )}
          <Button variant="outline" onClick={() => window.print()}>
            Print label
          </Button>
        </PageHeader>
      </div>
      <div className="flex w-fit items-center gap-4 rounded-lg border p-3">
        {/* eslint-disable-next-line @next/next/no-img-element -- an SVG from this app's own API, behind sign-in */}
        <img src={`/api/v1${path}/qr.svg`} alt={`QR code for ${instrument.instrumentCode}`} className="size-28" />
        <div className="text-sm">
          <p className="font-semibold">{instrument.instrumentCode}</p>
          <p>{instrument.name}</p>
          <p>Due {instrument.nextDueOn}</p>
        </div>
      </div>
      <div className="space-y-6 print:hidden">
        <Section title="Instrument">
          <Details
            items={[
              ["Category", instrument.category],
              ["Manufacturer", instrument.manufacturer],
              ["Model", instrument.model],
              ["Serial number", instrument.serialNumber],
              ["Facility", facility],
              ["Location", instrument.location],
              ["Custodian", instrument.custodianName],
              ["Calibrate every", `${instrument.frequencyDays} days`],
              ["Last calibrated", instrument.lastCalibratedOn],
              ["Next due", instrument.nextDueOn],
              ...(instrument.statusReason ? [["Status reason", instrument.statusReason] as [string, string]] : []),
            ]}
          />
        </Section>
        <Section title="Calibration history">
          <CalibrationHistory records={records} canManage={canManage && !retired} />
        </Section>
        <Section title="Usage history">
          <InstrumentUsage uses={uses} />
        </Section>
      </div>
      {dialog === "calibrate" && (
        <RecordDialog
          title="Record calibration"
          fields={CALIBRATION_FIELDS}
          onClose={close}
          onSubmit={async (values) => {
            await api.post(`${path}/calibrations`, values);
            refresh();
          }}
        />
      )}
      {dialog === "edit" && (
        <RecordDialog
          title="Edit instrument"
          fields={instrumentFields(facilities, assignees)}
          record={instrument}
          onClose={close}
          onSubmit={async (values) => {
            await api.put(path, values);
            refresh();
          }}
        />
      )}
      {dialog === "status" && (
        <RecordDialog
          title="Change status"
          fields={STATUS_FIELDS}
          onClose={close}
          onSubmit={async (values) => {
            await api.post(`${path}/status`, values);
            refresh();
          }}
        />
      )}
    </div>
  );
}
