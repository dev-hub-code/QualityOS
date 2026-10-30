import { z } from "zod";

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
