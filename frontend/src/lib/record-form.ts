import { isValidGstin } from "@/lib/gstin";
import { z } from "zod";

export interface FieldOption {
  value: string;
  label: string;
}

export const GSTIN_MESSAGE = "Not a valid GSTIN: check the 15 characters, the last is a check character";

export interface FieldDef {
  name: string;
  label: string;
  type?: "text" | "textarea" | "email" | "date" | "time" | "decimal" | "select" | "checkbox";
  required?: boolean;
  options?: FieldOption[];
  /** Where a new option can be added from the form. The list must be cached under this same path. */
  addPath?: string;
  /**
   * A dropdown of India's states, or of the cities of the state chosen in the form's "state" field, with Other for a
   * city the list lacks.
   */
  suggest?: "state" | "city";
}

/** Matches NUMERIC(18,6), so that the database never has to round a value. */
export const DECIMAL_PATTERN = /^\d{1,12}(\.\d{1,6})?$/;

/** Says what is wrong with a value DECIMAL_PATTERN refuses, rather than one message for every case. */
export function decimalProblem(value: string): string {
  const number = Number(value);
  if (value === "" || Number.isNaN(number)) return "Enter a number";
  if (number < 0) return "Cannot be negative";
  if (/\.\d{7,}$/.test(value)) return "Use at most 6 decimal places";
  if (Math.abs(number) >= 1e12) return "Enter a smaller number";
  return "Enter a number";
}

/** A decimal field's check, with a message that fits the mistake. */
export function decimalCheck(value: string, ctx: z.RefinementCtx) {
  if (value !== "" && !DECIMAL_PATTERN.test(value)) ctx.addIssue({ code: "custom", message: decimalProblem(value) });
}

function fieldSchema(field: FieldDef): z.ZodType {
  if (field.type === "checkbox") return z.boolean();
  let text = z.string().trim();
  if (field.required) text = text.min(1, `${field.label} is required`);
  if (field.type === "decimal") {
    return text.superRefine(decimalCheck);
  }
  if (field.name === "gstin") {
    return text
      .toUpperCase()
      .refine((value) => value === "" || isValidGstin(value), GSTIN_MESSAGE);
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
