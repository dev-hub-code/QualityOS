import { PRIORITY_LABELS } from "@/lib/manufacturing";
import type { FieldDef, FieldOption } from "@/lib/record-form";

const PRIORITIES: FieldOption[] = Object.entries(PRIORITY_LABELS).map(([value, label]) => ({ value, label }));

/** A work order raised from a plan line takes the line's product and the plan's facility, so those are not asked. */
export function workOrderFields(products: FieldOption[], facilities: FieldOption[], fromPlan = false): FieldDef[] {
  const fields: FieldDef[] = fromPlan
    ? []
    : [
        { name: "productId", label: "Product", type: "select", required: true, options: products },
        { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
      ];
  return [
    ...fields,
    { name: "quantity", label: "Quantity, in the product's unit", type: "decimal", required: true },
    { name: "plannedStart", label: "Planned start", type: "date", required: true },
    { name: "plannedEnd", label: "Planned end", type: "date", required: true },
    { name: "priority", label: "Priority", type: "select", options: PRIORITIES },
    { name: "notes", label: "Notes", type: "textarea" },
  ];
}

export function planFields(facilities: FieldOption[]): FieldDef[] {
  return [
    { name: "name", label: "Name", required: true },
    { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
    { name: "periodStart", label: "Period starts", type: "date", required: true },
    { name: "periodEnd", label: "Period ends", type: "date", required: true },
    { name: "notes", label: "Notes", type: "textarea" },
  ];
}

export function planLineFields(products: FieldOption[]): FieldDef[] {
  return [
    { name: "productId", label: "Product", type: "select", required: true, options: products },
    { name: "quantity", label: "Quantity, in the product's unit", type: "decimal", required: true },
    { name: "dueDate", label: "Due", type: "date", required: true },
    { name: "notes", label: "Notes", type: "textarea" },
  ];
}
