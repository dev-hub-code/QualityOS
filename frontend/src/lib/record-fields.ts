import type { FieldDef, FieldOption } from "@/lib/record-form";

export const FACILITY_FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "address", label: "Address" },
  { name: "city", label: "City" },
  { name: "state", label: "State" },
  { name: "postalCode", label: "Postal code" },
  { name: "gstin", label: "GSTIN" },
];

export const supplierFields = (categories: FieldOption[]): FieldDef[] => [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "legalName", label: "Legal name" },
  { name: "gstin", label: "GSTIN" },
  { name: "categoryId", label: "Category", type: "select", options: categories },
  { name: "email", label: "Email", type: "email" },
  { name: "phone", label: "Phone" },
  { name: "address", label: "Address" },
  { name: "city", label: "City" },
  { name: "state", label: "State" },
  { name: "postalCode", label: "Postal code" },
];

export const materialFields = (units: FieldOption[], categories: FieldOption[]): FieldDef[] => [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "grade", label: "Grade" },
  { name: "description", label: "Description" },
  { name: "uomId", label: "Unit", type: "select", required: true, options: units },
  { name: "categoryId", label: "Category", type: "select", options: categories },
  { name: "inspectionRequired", label: "Inspect on receipt", type: "checkbox" },
];

export const productFields = (units: FieldOption[], categories: FieldOption[]): FieldDef[] => [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "description", label: "Description" },
  {
    name: "productType",
    label: "Type",
    type: "select",
    required: true,
    options: [
      { value: "FINISHED", label: "Finished" },
      { value: "SEMI_FINISHED", label: "Semi-finished" },
    ],
  },
  { name: "uomId", label: "Unit", type: "select", required: true, options: units },
  { name: "categoryId", label: "Category", type: "select", options: categories },
];

export const INSPECTION_TYPES: FieldOption[] = [
  { value: "IQC", label: "Incoming (IQC)" },
  { value: "IPQC", label: "In-process (IPQC)" },
  { value: "FQC", label: "Final (FQC)" },
  { value: "OQC", label: "Outgoing (OQC)" },
  { value: "SUPPLIER", label: "Supplier" },
  { value: "PACKAGING", label: "Packaging" },
  { value: "VISUAL", label: "Visual" },
  { value: "DIMENSIONAL", label: "Dimensional" },
  { value: "FUNCTIONAL", label: "Functional" },
];

export const templateFields = (materials: FieldOption[]): FieldDef[] => [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "inspectionType", label: "Type", type: "select", required: true, options: INSPECTION_TYPES },
  { name: "materialId", label: "Material (blank for any)", type: "select", options: materials },
];
