import type { FieldDef, FieldOption } from "@/lib/record-form";

export function instrumentFields(facilities: FieldOption[], people: FieldOption[]): FieldDef[] {
  return [
    { name: "instrumentCode", label: "Instrument ID", required: true },
    { name: "name", label: "Name", required: true },
    { name: "category", label: "Category", required: true },
    { name: "manufacturer", label: "Manufacturer" },
    { name: "model", label: "Model" },
    { name: "serialNumber", label: "Serial number" },
    { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
    { name: "location", label: "Location" },
    { name: "custodianId", label: "Custodian", type: "select", options: people },
    { name: "frequencyDays", label: "Calibrate every (days)", type: "decimal", required: true },
    { name: "nextDueOn", label: "Next calibration due", type: "date", required: true },
  ];
}
