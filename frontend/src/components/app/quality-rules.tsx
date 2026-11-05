"use client";

import { FormError } from "@/components/forms/field";
import { Label } from "@/components/ui/label";
import { useOrganizationSettings, useSaveOrganizationSettings } from "@/hooks/use-organization-settings";
import { usePermission } from "@/hooks/use-me";
import type { SeverityThreshold } from "@/lib/types";

const THRESHOLDS: { value: SeverityThreshold; label: string }[] = [
  { value: "MINOR", label: "Every NCR" },
  { value: "MAJOR", label: "Critical and major NCRs" },
  { value: "CRITICAL", label: "Critical NCRs only" },
  { value: "NEVER", label: "No NCRs" },
];

const RULES = [
  {
    key: "capaRequiredFrom",
    label: "A closed CAPA is needed before verification for",
  },
  {
    key: "effectivenessRequiredFrom",
    label: "A CAPA's fix must be verified as effective for",
  },
] as const;

/** The settings section where an organisation decides how strict its NCR and CAPA workflow is. */
export function QualityRulesSettings() {
  const canManage = usePermission("TENANT_MANAGE");
  const { data } = useOrganizationSettings();
  const update = useSaveOrganizationSettings();
  if (!data) return null;
  return (
    <section className="space-y-3">
      <h2 className="font-medium">NCR and CAPA rules</h2>
      <p className="text-sm text-muted-foreground">
        A change applies to open NCRs at once. A CAPA keeps the verification rule it was raised under.
      </p>
      <FormError message={update.error?.message} />
      {RULES.map(({ key, label }) => (
        <div key={key} className="space-y-1">
          <Label htmlFor={key} className="font-normal">
            {label}
          </Label>
          <select
            id={key}
            className="h-9 w-full rounded-md border bg-background px-3 text-sm sm:w-72"
            value={data[key]}
            disabled={!canManage || update.isPending}
            onChange={(event) => update.mutate({ [key]: event.target.value as SeverityThreshold })}
          >
            {THRESHOLDS.map(({ value, label: text }) => (
              <option key={value} value={value}>
                {text}
              </option>
            ))}
          </select>
        </div>
      ))}
    </section>
  );
}
