import { describe, expect, it } from "vitest";
import { decimalProblem, formatQuantity, recordSchema, toFormValues, toPayload, type FieldDef } from "./record-form";

const fields: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "quantity", label: "Quantity", type: "decimal" },
  { name: "email", label: "Email", type: "email" },
  { name: "active", label: "Active", type: "checkbox" },
];
const valid = { name: "Bar", quantity: "", email: "", active: true };

describe("recordSchema", () => {
  it("requires required fields", () => {
    expect(recordSchema(fields).safeParse({ ...valid, name: " " }).success).toBe(false);
    expect(recordSchema(fields).safeParse(valid).success).toBe(true);
  });

  it("refuses decimals the database would round", () => {
    const parse = (quantity: string) => recordSchema(fields).safeParse({ ...valid, quantity }).success;
    expect(parse("19.900001")).toBe(true);
    expect(parse("1.0000001")).toBe(false);
    expect(parse("abc")).toBe(false);
  });
});

describe("conversions", () => {
  it("turns blanks into nulls and back", () => {
    expect(toPayload({ a: "", b: "x", c: false })).toEqual({ a: null, b: "x", c: false });
    expect(toFormValues(fields, { name: "Bar", email: null })).toEqual({
      name: "Bar",
      quantity: "",
      email: "",
      active: false,
    });
  });

  it("formats quantities without changing whole numbers", () => {
    expect(formatQuantity("250.000000")).toBe("250");
    expect(formatQuantity("19.900100")).toBe("19.9001");
    expect(formatQuantity("100")).toBe("100");
  });
});

describe("decimal field messages", () => {
  it("says what is wrong rather than one message for every mistake", () => {
    expect(decimalProblem("abc")).toBe("Enter a number");
    expect(decimalProblem("-5")).toBe("Cannot be negative");
    expect(decimalProblem("1.1234567")).toBe("Use at most 6 decimal places");
    expect(decimalProblem("1234567890123")).toBe("Enter a smaller number");
  });

  it("puts the message on the field", () => {
    const schema = recordSchema([{ name: "quantity", label: "Quantity", type: "decimal" }]);
    const result = schema.safeParse({ quantity: "-2" });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0].message).toBe("Cannot be negative");
    expect(schema.safeParse({ quantity: "2.5" }).success).toBe(true);
    expect(schema.safeParse({ quantity: "" }).success).toBe(true);
  });
});
