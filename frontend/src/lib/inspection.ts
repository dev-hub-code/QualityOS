import { DECIMAL_PATTERN, formatQuantity } from "@/lib/record-form";
import type { InspectionResult, ParameterSpec } from "@/lib/types";

export const sampleKey = (code: string, sample: number) => `${code}#${sample}`;

export const samplesOf = (parameter: ParameterSpec, sampleSize: number) =>
  parameter.perSample ? sampleSize : 1;

/** The requirement in words, shown beside the input. */
export function requirement(parameter: ParameterSpec): string {
  const { minValue, maxValue, nominalValue, tolerance, expectedText } = parameter;
  if (parameter.dataType !== "NUMERIC") return expectedText ?? "Reviewer decides";
  if (minValue && maxValue) return `${formatQuantity(minValue)} to ${formatQuantity(maxValue)}`;
  if (nominalValue && tolerance) return `${formatQuantity(nominalValue)} ± ${formatQuantity(tolerance)}`;
  if (minValue) return `At least ${formatQuantity(minValue)}`;
  if (maxValue) return `At most ${formatQuantity(maxValue)}`;
  return nominalValue ? formatQuantity(nominalValue) : "Reviewer decides";
}

export function resultValue(result: InspectionResult): string {
  if (result.valueNumeric !== null) return formatQuantity(result.valueNumeric);
  if (result.valueBoolean !== null) return String(result.valueBoolean);
  return result.valueDate ?? result.valueText ?? "";
}

export function toFormValues(results: InspectionResult[]): Record<string, string> {
  return Object.fromEntries(
    results.map((result) => [sampleKey(result.parameterCode, result.sampleNumber), resultValue(result)]),
  );
}

export interface ResultEntry {
  parameterCode: string;
  sampleNumber: number;
  valueNumeric?: string;
  valueText?: string;
  valueBoolean?: boolean;
  valueDate?: string;
}

/** Entries for every filled input. A partial entry is fine: the backend checks completeness on submission. */
export function toEntries(
  parameters: ParameterSpec[],
  sampleSize: number,
  values: Record<string, string>,
): { entries: ResultEntry[]; invalid: string[] } {
  const entries: ResultEntry[] = [];
  const invalid: string[] = [];
  for (const parameter of parameters) {
    for (let sample = 1; sample <= samplesOf(parameter, sampleSize); sample++) {
      const value = values[sampleKey(parameter.code, sample)]?.trim();
      if (!value) continue;
      const entry: ResultEntry = { parameterCode: parameter.code, sampleNumber: sample };
      if (parameter.dataType === "NUMERIC") {
        const digits = value.replace(/^-/, "");
        if (!DECIMAL_PATTERN.test(digits)) {
          invalid.push(`${parameter.code} sample ${sample}`);
          continue;
        }
        entry.valueNumeric = value;
      } else if (parameter.dataType === "BOOLEAN") {
        entry.valueBoolean = value === "true";
      } else if (parameter.dataType === "DATE") {
        entry.valueDate = value;
      } else {
        entry.valueText = value;
      }
      entries.push(entry);
    }
  }
  return { entries, invalid };
}
