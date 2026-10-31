"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Details, PageHeader, QueryState, Section, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { FACILITY_FIELDS } from "@/lib/record-fields";
import type { FieldDef } from "@/lib/record-form";
import type { FacilityDetail } from "@/lib/types";

const UNIT_FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
];
const SHIFT_FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "startsAt", label: "Starts at", type: "time", required: true },
  { name: "endsAt", label: "Ends at", type: "time", required: true },
];

type Dialog = "edit" | "department" | "line" | "shift" | null;

function UnitList({
  items,
  onRemove,
}: {
  items: { id: string; label: string }[];
  onRemove?: (id: string) => void;
}) {
  if (items.length === 0) return <p className="text-sm text-muted-foreground">None yet.</p>;
  return (
    <ul className="divide-y text-sm">
      {items.map((item) => (
        <li key={item.id} className="flex items-center justify-between py-2">
          {item.label}
          {onRemove && (
            <Button variant="ghost" size="sm" onClick={() => onRemove(item.id)}>
              Remove
            </Button>
          )}
        </li>
      ))}
    </ul>
  );
}

export default function FacilityPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const path = `/facilities/${id}`;
  const canManage = usePermission("FACILITY_MANAGE");
  const [dialog, setDialog] = useState<Dialog>(null);
  const { run, message } = useAction();
  const refresh = useRefresh();
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<FacilityDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { facility, departments, productionLines, shifts } = data;
  const add = (button: string, target: Dialog) =>
    canManage && (
      <Button variant="outline" size="sm" onClick={() => setDialog(target)}>
        {button}
      </Button>
    );
  const remover = (segment: string) =>
    canManage ? (unitId: string) => run(() => api.delete(`${path}/${segment}/${unitId}`)) : undefined;

  return (
    <div className="space-y-6">
      <PageHeader title={facility.name} subtitle={facility.code}>
        {canManage && (
          <>
            <Button variant="outline" onClick={() => setDialog("edit")}>
              Edit
            </Button>
            <Button
              variant="destructive"
              onClick={() => run(() => api.delete(path).then(() => router.push("/facilities")))}
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
            ["Address", facility.address],
            ["City", facility.city],
            ["State", facility.state],
            ["Postal code", facility.postalCode],
            ["GSTIN", facility.gstin],
          ]}
        />
      </Section>
      <div className="grid gap-6 lg:grid-cols-3">
        <Section title="Departments" action={add("Add department", "department")}>
          <UnitList
            items={departments.map((unit) => ({ id: unit.id, label: `${unit.code} – ${unit.name}` }))}
            onRemove={remover("departments")}
          />
        </Section>
        <Section title="Production lines" action={add("Add line", "line")}>
          <UnitList
            items={productionLines.map((unit) => ({ id: unit.id, label: `${unit.code} – ${unit.name}` }))}
            onRemove={remover("production-lines")}
          />
        </Section>
        <Section title="Shifts" action={add("Add shift", "shift")}>
          <UnitList
            items={shifts.map((shift) => ({
              id: shift.id,
              label: `${shift.name} (${shift.startsAt.slice(0, 5)}–${shift.endsAt.slice(0, 5)})`,
            }))}
            onRemove={remover("shifts")}
          />
        </Section>
      </div>
      {dialog === "edit" && (
        <RecordDialog
          title="Edit facility"
          fields={FACILITY_FIELDS}
          record={facility}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, { ...values, version: facility.version }).then(refresh)}
        />
      )}
      {(dialog === "department" || dialog === "line") && (
        <RecordDialog
          title={dialog === "line" ? "Add production line" : "Add department"}
          fields={UNIT_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api
              .post(`${path}/${dialog === "line" ? "production-lines" : "departments"}`, values)
              .then(refresh)
          }
        />
      )}
      {dialog === "shift" && (
        <RecordDialog
          title="Add shift"
          fields={SHIFT_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/shifts`, values).then(refresh)}
        />
      )}
    </div>
  );
}
