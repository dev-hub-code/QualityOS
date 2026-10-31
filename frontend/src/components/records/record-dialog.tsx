"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMemo, useState } from "react";
import { useForm, type FieldValues } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { recordSchema, toFormValues, toPayload, type FieldDef } from "@/lib/record-form";

interface RecordDialogProps {
  title: string;
  fields: FieldDef[];
  record?: object;
  onSubmit: (values: Record<string, unknown>) => Promise<unknown>;
  onClose: () => void;
}

const SELECT_CLASS = "h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm";
const TEXTAREA_CLASS = "w-full rounded-md border border-input bg-transparent px-2 py-1.5 text-sm";

/** A dialog form for the many small records that need nothing beyond their fields. */
export function RecordDialog({ title, fields, record, onSubmit, onClose }: RecordDialogProps) {
  const [formError, setFormError] = useState("");
  const schema = useMemo(() => recordSchema(fields), [fields]);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FieldValues>({
    resolver: zodResolver(schema as never),
    defaultValues: toFormValues(fields, record as Record<string, unknown> | undefined),
  });

  async function submit(values: FieldValues) {
    setFormError("");
    try {
      await onSubmit(toPayload(values));
      onClose();
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit(submit)} className="space-y-4" noValidate>
          <FormError message={formError} />
          {fields.map((field) => {
            const id = `field-${field.name}`;
            const error = errors[field.name] as never;
            if (field.type === "checkbox") {
              return (
                <div key={field.name} className="flex items-center gap-2">
                  <input id={id} type="checkbox" className="size-4" {...register(field.name)} />
                  <Label htmlFor={id} className="font-normal">
                    {field.label}
                  </Label>
                </div>
              );
            }
            if (field.type === "select") {
              return (
                <div key={field.name} className="space-y-1.5">
                  <Label htmlFor={id}>{field.label}</Label>
                  <select id={id} className={SELECT_CLASS} {...register(field.name)}>
                    <option value="">Select…</option>
                    {field.options?.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                  {errors[field.name] && (
                    <p className="text-sm text-destructive">{String(errors[field.name]?.message)}</p>
                  )}
                </div>
              );
            }
            if (field.type === "textarea") {
              return (
                <div key={field.name} className="space-y-1.5">
                  <Label htmlFor={id}>{field.label}</Label>
                  <textarea id={id} rows={3} className={TEXTAREA_CLASS} {...register(field.name)} />
                  {errors[field.name] && (
                    <p className="text-sm text-destructive">{String(errors[field.name]?.message)}</p>
                  )}
                </div>
              );
            }
            return (
              <Field
                key={field.name}
                id={id}
                label={field.label}
                type={field.type === "decimal" ? "text" : (field.type ?? "text")}
                inputMode={field.type === "decimal" ? "decimal" : undefined}
                error={error}
                {...register(field.name)}
              />
            );
          })}
          <Button type="submit" disabled={isSubmitting}>
            Save
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
