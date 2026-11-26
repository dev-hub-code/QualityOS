"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Section, useRefresh, useUnits } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { yesNoValue } from "@/lib/inspection";
import { formatQuantity } from "@/lib/record-form";
import { templateDraftSchema, type TemplateDraftValues } from "@/lib/schemas";
import type { ParameterSpec, TemplateVersion } from "@/lib/types";

const SELECT_CLASS = "h-9 w-full rounded-xl border border-input bg-white/70 px-2 text-sm";
/** The AQL columns of the ISO 2859-1 tables. The server refuses any other value. */
const AQLS = ["0.010", "0.015", "0.025", "0.040", "0.065", "0.10", "0.15", "0.25", "0.40", "0.65", "1.0", "1.5", "2.5", "4.0", "6.5", "10"];

const blankParameter: TemplateDraftValues["parameters"][number] = {
  code: "",
  name: "",
  dataType: "NUMERIC",
  uomId: "",
  minValue: "",
  maxValue: "",
  nominalValue: "",
  tolerance: "",
  expectedText: "",
  criticality: "MINOR",
  mandatory: true,
  perSample: true,
  evidenceRequired: false,
  inspectionMethod: "",
};

function toParameterValues(parameter: ParameterSpec): TemplateDraftValues["parameters"][number] {
  return {
    ...parameter,
    uomId: parameter.uomId ?? "",
    minValue: formatQuantity(parameter.minValue),
    maxValue: formatQuantity(parameter.maxValue),
    nominalValue: formatQuantity(parameter.nominalValue),
    tolerance: formatQuantity(parameter.tolerance),
    expectedText:
      parameter.dataType === "BOOLEAN" ? yesNoValue(parameter.expectedText) : (parameter.expectedText ?? ""),
    inspectionMethod: parameter.inspectionMethod ?? "",
  };
}

const blankToNull = (value: string) => value || null;

/** Everything about a draft is saved together, so a half-edited parameter never reaches an inspection. */
export function TemplateDraftForm({ templateId, draft }: { templateId: string; draft: TemplateVersion }) {
  const units = useUnits();
  const refresh = useRefresh();
  const [formError, setFormError] = useState("");
  const [saved, setSaved] = useState(false);
  const {
    register,
    control,
    watch,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<TemplateDraftValues>({
    resolver: zodResolver(templateDraftSchema),
    defaultValues: {
      changeNote: draft.changeNote ?? "",
      samplingType: draft.samplingType,
      sampleSize: draft.sampleSize === null ? "1" : String(draft.sampleSize),
      samplePercent: draft.samplePercent ?? "",
      aql: AQLS.find((aql) => draft.aql !== null && Number(aql) === Number(draft.aql)) ?? "2.5",
      inspectionLevel: draft.inspectionLevel ?? "II",
      doubleSampling: draft.doubleSampling ?? false,
      acceptanceNumber: String(draft.acceptanceNumber),
      parameters: draft.parameters.map(toParameterValues),
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "parameters" });
  const samplingType = watch("samplingType");

  async function onSubmit(values: TemplateDraftValues) {
    setFormError("");
    setSaved(false);
    try {
      await api.put(`/inspection-templates/${templateId}/versions/${draft.id}`, {
        changeNote: blankToNull(values.changeNote),
        samplingType: values.samplingType,
        sampleSize: values.samplingType === "FIXED" ? Number(values.sampleSize) : null,
        samplePercent: values.samplingType === "PERCENTAGE" ? values.samplePercent : null,
        aql: values.samplingType === "AQL" ? values.aql : null,
        inspectionLevel: values.samplingType === "AQL" ? values.inspectionLevel : null,
        doubleSampling: values.samplingType === "AQL" && values.doubleSampling,
        acceptanceNumber: Number(values.acceptanceNumber),
        parameters: values.parameters.map((parameter) => ({
          ...parameter,
          uomId: blankToNull(parameter.uomId),
          minValue: blankToNull(parameter.minValue),
          maxValue: blankToNull(parameter.maxValue),
          nominalValue: blankToNull(parameter.nominalValue),
          tolerance: blankToNull(parameter.tolerance),
          expectedText: blankToNull(parameter.expectedText),
          inspectionMethod: blankToNull(parameter.inspectionMethod),
        })),
      });
      await refresh();
      setSaved(true);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <form
      // A message from the last attempt goes, even when this one stops in the browser.
      onSubmit={(event) => {
        setFormError("");
        return handleSubmit(onSubmit)(event);
      }} className="space-y-6" noValidate>
      <FormError message={formError} />
      <Section title={`Draft version ${draft.versionNumber}`}>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="samplingType">Sampling</Label>
            <select id="samplingType" className={SELECT_CLASS} {...register("samplingType")}>
              <option value="FIXED">Fixed number of samples</option>
              <option value="PERCENTAGE">Percentage of the lot</option>
              <option value="FULL">Every unit</option>
              <option value="AQL">AQL (ISO 2859-1)</option>
            </select>
          </div>
          {samplingType === "FIXED" && (
            <Field label="Sample size" id="sampleSize" inputMode="numeric" error={errors.sampleSize} {...register("sampleSize")} />
          )}
          {samplingType === "PERCENTAGE" && (
            <Field label="Sample percent" id="samplePercent" inputMode="decimal" error={errors.samplePercent} {...register("samplePercent")} />
          )}
          {samplingType === "AQL" ? (
            <>
              <div className="space-y-1.5">
                <Label htmlFor="aql">AQL (% nonconforming)</Label>
                <select id="aql" className={SELECT_CLASS} {...register("aql")}>
                  {AQLS.map((aql) => (
                    <option key={aql} value={aql}>
                      {aql}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="inspectionLevel">Inspection level</Label>
                <select id="inspectionLevel" className={SELECT_CLASS} {...register("inspectionLevel")}>
                  <option value="I">I (smaller samples)</option>
                  <option value="II">II (usual)</option>
                  <option value="III">III (larger samples)</option>
                  <option value="S1">S-1 (special, smallest samples)</option>
                  <option value="S2">S-2 (special)</option>
                  <option value="S3">S-3 (special)</option>
                  <option value="S4">S-4 (special)</option>
                </select>
              </div>
              <label className="flex items-center gap-2 text-sm sm:col-span-2">
                <input type="checkbox" className="size-4" {...register("doubleSampling")} />
                Double sampling: a smaller first sample, and a second one only when the first is inconclusive
              </label>
            </>
          ) : (
            <Field
              label="Failures allowed per parameter"
              id="acceptanceNumber"
              inputMode="numeric"
              error={errors.acceptanceNumber}
              {...register("acceptanceNumber")}
            />
          )}
          <Field label="Change note" id="changeNote" {...register("changeNote")} />
        </div>
        <p className="mt-3 text-sm text-muted-foreground">
          Critical parameters always fail on one bad sample.
          {samplingType === "AQL" &&
            " Under AQL, each lot's size sets the sample size and how many nonconforming samples it accepts. Double sampling uses the standard's double plans where it has one, and single sampling elsewhere. The switching rules move each supplier's lots between normal, tightened and reduced inspection. Special levels S-1 to S-4 suit costly or destructive tests. A sample failing several parameters counts once."}
        </p>
      </Section>
      {fields.map((field, index) => {
        // eslint-disable-next-line react-hooks/incompatible-library -- nothing here is memoized
        const dataType = watch(`parameters.${index}.dataType`);
        const fieldErrors = errors.parameters?.[index];
        return (
          <Section
            key={field.id}
            title={`Parameter ${index + 1}`}
            action={
              <Button type="button" variant="ghost" size="sm" onClick={() => remove(index)}>
                Remove
              </Button>
            }
          >
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <Field label="Parameter code" id={`code-${index}`} error={fieldErrors?.code} {...register(`parameters.${index}.code`)} />
              <Field label="Parameter name" id={`name-${index}`} error={fieldErrors?.name} {...register(`parameters.${index}.name`)} />
              <div className="space-y-1.5">
                <Label htmlFor={`type-${index}`}>Data type</Label>
                <select id={`type-${index}`} className={SELECT_CLASS} {...register(`parameters.${index}.dataType`)}>
                  <option value="NUMERIC">Number</option>
                  <option value="TEXT">Text</option>
                  <option value="BOOLEAN">Yes or no</option>
                  <option value="DATE">Date</option>
                </select>
              </div>
              {dataType === "NUMERIC" ? (
                <>
                  <Field label="Minimum" id={`min-${index}`} inputMode="decimal" error={fieldErrors?.minValue} {...register(`parameters.${index}.minValue`)} />
                  <Field label="Maximum" id={`max-${index}`} inputMode="decimal" error={fieldErrors?.maxValue} {...register(`parameters.${index}.maxValue`)} />
                  <Field label="Nominal" id={`nominal-${index}`} inputMode="decimal" error={fieldErrors?.nominalValue} {...register(`parameters.${index}.nominalValue`)} />
                  <Field label="Tolerance (±)" id={`tolerance-${index}`} inputMode="decimal" error={fieldErrors?.tolerance} {...register(`parameters.${index}.tolerance`)} />
                  <div className="space-y-1.5">
                    <Label htmlFor={`unit-${index}`}>Unit</Label>
                    <select id={`unit-${index}`} className={SELECT_CLASS} {...register(`parameters.${index}.uomId`)}>
                      <option value="">None</option>
                      {units.options.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </div>
                </>
              ) : (
                dataType === "BOOLEAN" ? (
                  <div className="space-y-1.5">
                    <Label htmlFor={`expected-${index}`}>Expected answer</Label>
                    <select id={`expected-${index}`} className={SELECT_CLASS} {...register(`parameters.${index}.expectedText`)}>
                      <option value="">Reviewer decides</option>
                      <option value="true">Yes</option>
                      <option value="false">No</option>
                    </select>
                  </div>
                ) : (
                  dataType !== "DATE" && (
                    <Field label="Expected text" id={`expected-${index}`} {...register(`parameters.${index}.expectedText`)} />
                  )
                )
              )}
              <div className="space-y-1.5">
                <Label htmlFor={`criticality-${index}`}>Criticality</Label>
                <select id={`criticality-${index}`} className={SELECT_CLASS} {...register(`parameters.${index}.criticality`)}>
                  <option value="MINOR">Minor</option>
                  <option value="MAJOR">Major</option>
                  <option value="CRITICAL">Critical</option>
                </select>
              </div>
              <Field label="Method" id={`method-${index}`} {...register(`parameters.${index}.inspectionMethod`)} />
            </div>
            <div className="mt-4 flex flex-wrap gap-4 text-sm">
              {(
                [
                  ["mandatory", "Mandatory"],
                  ["perSample", "Measure every sample"],
                  ["evidenceRequired", "Evidence required"],
                ] as const
              ).map(([name, label]) => (
                <label key={name} className="flex items-center gap-2">
                  <input type="checkbox" className="size-4" {...register(`parameters.${index}.${name}`)} />
                  {label}
                </label>
              ))}
            </div>
          </Section>
        );
      })}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" onClick={() => append(blankParameter)}>
          Add parameter
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          Save draft
        </Button>
        {saved && <span className="text-sm text-muted-foreground">Saved</span>}
      </div>
    </form>
  );
}
