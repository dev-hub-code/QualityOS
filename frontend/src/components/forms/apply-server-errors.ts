import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { ApiError } from "@/lib/api";

/** Shows backend validation on its fields, and returns any message that belongs to the form as a whole. */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
): string {
  if (!(error instanceof ApiError)) return "Something went wrong. Please try again.";
  error.violations.forEach((violation) =>
    setError(violation.field as Path<T>, { message: violation.message }),
  );
  return error.violations.length > 0 ? "" : error.message;
}
