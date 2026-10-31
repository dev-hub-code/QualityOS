import { z } from "zod";

export interface FieldOption {
  value: string;
  label: string;
}

export interface FieldDef {
  name: string;
  label: string;
  type?: "text" | "email" | "date" | "time" | "decimal" | "select" | "checkbox";
  required?: boolean;
  options?: FieldOption[];
}

/** Matches NUMERIC(18,6), so that the database never has to round a value. */
export const DECIMAL_PATTERN = /^\d{1,12}(\.\d{1,6})?$/;
const DECIMAL_MESSAGE = "Enter a number with at most 6 decimal places";

function fieldSchema(field: FieldDef): z.ZodType {
  if (field.type === "checkbox") return z.boolean();
  let text = z.string().trim();
  if (field.required) text = text.min(1, `${field.label} is required`);
  if (field.type === "decimal") {
    return text.refine((value) => value === "" || DECIMAL_PATTERN.test(value), DECIMAL_MESSAGE);
  }
  if (field.type === "email") {
    return text.refine((value) => value === "" || z.email().safeParse(value).success, "Enter a valid email");
  }
  return text;
}

export function recordSchema(fields: FieldDef[]) {
  return z.object(Object.fromEntries(fields.map((field) => [field.name, fieldSchema(field)])));
}

/** Blank inputs mean "no value" to the API. */
export function toPayload(values: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(values).map(([key, value]) => [key, value === "" ? null : value]),
  );
}

/** Values for a form, where the API's nulls become blank inputs. */
export function toFormValues(fields: FieldDef[], record: Record<string, unknown> = {}) {
  return Object.fromEntries(
    fields.map((field) => [field.name, record[field.name] ?? (field.type === "checkbox" ? false : "")]),
  );
}

/** "250.000000" reads as "250". */
export function formatQuantity(value: string | null): string {
  if (value === null) return "";
  return value.includes(".") ? value.replace(/\.?0+$/, "") : value;
}
