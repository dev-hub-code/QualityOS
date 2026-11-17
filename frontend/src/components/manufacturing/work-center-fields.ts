import type { FieldDef, FieldOption } from "@/lib/record-form";

export function workCenterFields(facilities: FieldOption[], lines: FieldOption[]): FieldDef[] {
  return [
    { name: "code", label: "Code", required: true },
    { name: "name", label: "Name", required: true },
    { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
    { name: "productionLineId", label: "Production line, if it sits on one", type: "select", options: lines },
    { name: "description", label: "Description", type: "textarea" },
    { name: "capacityUnits", label: "Operations it can run at once, if it has no machines", type: "decimal" },
    { name: "labourRate", label: "Labour cost per hour", type: "decimal" },
    { name: "machineRate", label: "Machine cost per hour", type: "decimal" },
    { name: "overheadRate", label: "Overhead per hour", type: "decimal" },
  ];
}

const SHIFT_FIELD = "worksShift_";

/** One box per facility shift; none ticked means the centre works them all. */
export function shiftFields(shifts: { id: string; name: string }[]): FieldDef[] {
  return shifts.map((shift) => ({ name: SHIFT_FIELD + shift.id, label: `Works the ${shift.name} shift`, type: "checkbox" }));
}

/** A work centre as the form holds it: numbers as text, and a box ticked for each shift it names. */
export function workCenterRecord(workCenter: { capacityUnits: number; shiftIds?: string[] } & object) {
  return {
    ...workCenter,
    capacityUnits: String(workCenter.capacityUnits),
    ...Object.fromEntries((workCenter.shiftIds ?? []).map((id) => [SHIFT_FIELD + id, true])),
  };
}

/** The form's values as the API takes them: the ticked shifts become shiftIds. */
export function workCenterRequest(values: Record<string, unknown>) {
  const request: Record<string, unknown> = {};
  const shiftIds: string[] = [];
  for (const [name, value] of Object.entries(values)) {
    if (name.startsWith(SHIFT_FIELD)) {
      if (value === true) shiftIds.push(name.slice(SHIFT_FIELD.length));
    } else {
      request[name] = value;
    }
  }
  return { ...request, shiftIds };
}

export function machineFields(workCenters: FieldOption[]): FieldDef[] {
  return [
    { name: "code", label: "Code", required: true },
    { name: "name", label: "Name", required: true },
    { name: "workCenterId", label: "Work centre", type: "select", required: true, options: workCenters },
    { name: "manufacturer", label: "Manufacturer" },
    { name: "model", label: "Model" },
    { name: "serialNumber", label: "Serial number" },
  ];
}
