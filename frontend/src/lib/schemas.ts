import { z } from "zod";
import { DECIMAL_PATTERN } from "@/lib/record-form";

const password = z
  .string()
  .min(10, "Use at least 10 characters")
  .max(128, "Use at most 128 characters");

export const loginSchema = z.object({
  email: z.email("Enter a valid email"),
  password: z.string().min(1, "Enter your password"),
});

export const registerSchema = z.object({
  fullName: z.string().trim().min(1, "Enter your name").max(200),
  email: z.email("Enter a valid email"),
  password,
  organizationName: z.string().trim().min(1, "Enter your organisation's name").max(200),
});

export const organizationSchema = z.object({
  name: z.string().trim().min(1, "Enter a name").max(200),
  legalName: z.string().trim().max(200),
  gstin: z.string().trim().toUpperCase().regex(/^$|^[0-9]{2}[A-Z0-9]{13}$/, "Must be a 15 character GSTIN"),
  industry: z.string().trim().max(100),
  address: z.string().trim().max(1000),
  timezone: z.string().trim().min(1, "Enter a time zone").max(64),
  currency: z.string().trim().toUpperCase().length(3, "Use a 3 letter code"),
});

export const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, "Enter your current password"),
  newPassword: password,
});

export const addMemberSchema = z.object({
  email: z.email("Enter a valid email"),
  fullName: z.string().trim().min(1, "Enter a name").max(200),
  initialPassword: password.or(z.literal("")),
});

export const roleSchema = z.object({
  name: z.string().trim().min(1, "Enter a name").max(100),
  description: z.string().trim().max(500),
});

export type LoginValues = z.infer<typeof loginSchema>;
export type RegisterValues = z.infer<typeof registerSchema>;
export type OrganizationValues = z.infer<typeof organizationSchema>;
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;
export type AddMemberValues = z.infer<typeof addMemberSchema>;
export type RoleValues = z.infer<typeof roleSchema>;

const decimal = z.string().trim().regex(DECIMAL_PATTERN, "Enter a number with at most 6 decimal places");
const positiveDecimal = decimal.refine((value) => Number(value) > 0, "Must be above zero");

export const purchaseOrderSchema = z.object({
  supplierId: z.string().min(1, "Choose a supplier"),
  facilityId: z.string().min(1, "Choose a facility"),
  orderDate: z.string().min(1, "Order date is required"),
  expectedDate: z.string(),
  items: z
    .array(
      z.object({
        materialId: z.string().min(1, "Choose a material"),
        quantity: positiveDecimal,
        unitPrice: decimal.or(z.literal("")),
      }),
    )
    .min(1),
});
export type PurchaseOrderValues = z.infer<typeof purchaseOrderSchema>;

export const receiptSchema = z.object({
  receivedOn: z.string().min(1, "Date is required"),
  deliveryNote: z.string().trim().max(100),
  items: z
    .array(
      z.object({
        purchaseOrderItemId: z.string().min(1),
        supplierBatch: z.string().trim().min(1, "Enter the supplier's batch"),
        quantity: positiveDecimal,
      }),
    )
    .min(1),
});
export type ReceiptValues = z.infer<typeof receiptSchema>;

const optionalDecimal = decimal.or(z.literal(""));
const signedDecimal = z
  .string()
  .trim()
  .refine((value) => value === "" || DECIMAL_PATTERN.test(value.replace(/^-/, "")), "Enter a valid number");
const wholeNumber = z.string().trim().regex(/^\d{1,3}$/, "Enter a whole number");

export const templateDraftSchema = z.object({
  changeNote: z.string().trim().max(1000),
  samplingType: z.enum(["FIXED", "PERCENTAGE", "FULL"]),
  sampleSize: wholeNumber,
  samplePercent: optionalDecimal,
  acceptanceNumber: wholeNumber,
  parameters: z.array(
    z.object({
      code: z.string().trim().min(1, "Enter a code").max(50),
      name: z.string().trim().min(1, "Enter a name").max(200),
      dataType: z.enum(["NUMERIC", "TEXT", "BOOLEAN", "DATE"]),
      uomId: z.string(),
      minValue: signedDecimal,
      maxValue: signedDecimal,
      nominalValue: signedDecimal,
      tolerance: optionalDecimal,
      expectedText: z.string().trim().max(500),
      criticality: z.enum(["CRITICAL", "MAJOR", "MINOR"]),
      mandatory: z.boolean(),
      perSample: z.boolean(),
      evidenceRequired: z.boolean(),
      inspectionMethod: z.string().trim().max(200),
    }),
  ),
});
export type TemplateDraftValues = z.infer<typeof templateDraftSchema>;
