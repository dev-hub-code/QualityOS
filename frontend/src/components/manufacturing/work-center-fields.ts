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

/** A work centre as the form holds it: numbers as text. */
export function workCenterRecord(workCenter: { capacityUnits: number } & object) {
  return { ...workCenter, capacityUnits: String(workCenter.capacityUnits) };
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
