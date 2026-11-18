"use client";

import { FormError } from "@/components/forms/field";
import { Label } from "@/components/ui/label";
import {
  useOrganizationSettings,
  useSaveOrganizationSettings,
} from "@/hooks/use-organization-settings";
import { usePermission } from "@/hooks/use-me";
import type { SeverityThreshold } from "@/lib/types";

const THRESHOLDS: { value: SeverityThreshold; label: string }[] = [
  { value: "MINOR", label: "Every NCR" },
  { value: "MAJOR", label: "Critical and major NCRs" },
  { value: "CRITICAL", label: "Critical NCRs only" },
  { value: "NEVER", label: "No NCRs" },
];

const ESCALATION_DAYS = [
  { value: 0, label: "Never" },
  { value: 3, label: "3 days late" },
  { value: 7, label: "7 days late" },
  { value: 14, label: "14 days late" },
  { value: 30, label: "30 days late" },
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
      <h2 className="text-base font-semibold text-[#1b2a55]">
        NCR and CAPA rules
      </h2>
      <p className="text-sm text-muted-foreground">
        A change applies to open NCRs at once. A CAPA keeps the verification
        rule it was raised under.
      </p>
      <FormError message={update.error?.message} />
      {RULES.map(({ key, label }) => (
        <div key={key} className="space-y-1">
          <Label htmlFor={key} className="font-normal">
            {label}
          </Label>
          <select
            id={key}
            className="h-9 w-full rounded-xl border bg-white/70 px-3 text-sm sm:w-72"
            value={data[key]}
            disabled={!canManage || update.isPending}
            onChange={(event) =>
              update.mutate({ [key]: event.target.value as SeverityThreshold })
            }
          >
            {THRESHOLDS.map(({ value, label: text }) => (
              <option key={value} value={value}>
                {text}
              </option>
            ))}
          </select>
        </div>
      ))}
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={data.actionEvidenceRequired}
          disabled={!canManage || update.isPending}
          onChange={(event) =>
            update.mutate({ actionEvidenceRequired: event.target.checked })
          }
        />
        A CAPA action needs evidence attached before it can be completed
      </label>
      <div className="space-y-1">
        <Label htmlFor="capaEscalationDays" className="font-normal">
          Tell everyone who approves CAPAs when a CAPA or one of its actions is
        </Label>
        <select
          id="capaEscalationDays"
          className="h-9 w-full rounded-xl border bg-white/70 px-3 text-sm sm:w-72"
          value={data.capaEscalationDays}
          disabled={!canManage || update.isPending}
          onChange={(event) =>
            update.mutate({ capaEscalationDays: Number(event.target.value) })
          }
        >
          {/* A value set some other way still shows as it is. */}
          {[
            ...ESCALATION_DAYS,
            ...(ESCALATION_DAYS.some(
              ({ value }) => value === data.capaEscalationDays,
            )
              ? []
              : [
                  {
                    value: data.capaEscalationDays,
                    label: `${data.capaEscalationDays} days late`,
                  },
                ]),
          ].map(({ value, label }) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <p className="text-xs text-muted-foreground">
          Once for each, besides the weekly reminders to its owner.
        </p>
      </div>
    </section>
  );
}
