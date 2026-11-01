"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useForm, type FieldValues } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { recordSchema, toFormValues, toPayload, type FieldDef } from "@/lib/record-form";

interface RecordDialogProps {
  title: string;
  fields: FieldDef[];
  record?: object;
  onSubmit: (values: Record<string, unknown>) => Promise<unknown>;
  onClose: () => void;
  /** Shown under the fields, for forms that add fields of their own. */
  extra?: ReactNode;
}

const SELECT_CLASS = "h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm";
const TEXTAREA_CLASS = "w-full rounded-md border border-input bg-transparent px-2 py-1.5 text-sm";

/** Adds to a list that may be empty, without leaving the form that needs it. */
function AddOption({ field, onAdded }: { field: FieldDef; onAdded: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const label = field.label.toLowerCase();

  async function add() {
    if (!name.trim()) {
      setError(`Enter a name for the ${label}`);
      return;
    }
    setError("");
    setPending(true);
    try {
      const { id } = await api.post<{ id: string }>(field.addPath as string, { name: name.trim() });
      onAdded(id);
      await queryClient.invalidateQueries({ queryKey: [field.addPath] });
      setName("");
      setOpen(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Something went wrong. Please try again.");
    } finally {
      setPending(false);
    }
  }

  // Enter belongs to this small form here, not to the record around it.
  function onKeyDown(event: KeyboardEvent) {
    if (event.key === "Enter") {
      event.preventDefault();
      add();
    }
  }

  if (!open) {
    return (
      <button type="button" className="text-sm text-primary underline underline-offset-4" onClick={() => setOpen(true)}>
        Add {label}
      </button>
    );
  }
  return (
    <div className="space-y-1">
      <div className="flex gap-2">
        <Input
          aria-label={`New ${label} name`}
          autoFocus
          maxLength={100}
          value={name}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={onKeyDown}
        />
        <Button type="button" variant="outline" size="sm" disabled={pending} onClick={add}>
          {pending ? "Adding…" : "Add"}
        </Button>
        <Button type="button" variant="ghost" size="sm" disabled={pending} onClick={() => setOpen(false)}>
          Cancel
        </Button>
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </div>
  );
}

/** A dialog form for the many small records that need nothing beyond their fields. */
export function RecordDialog({ title, fields, record, onSubmit, onClose, extra }: RecordDialogProps) {
  const [formError, setFormError] = useState("");
  const added = useRef<{ field: string; id: string } | null>(null);
  const schema = useMemo(() => recordSchema(fields), [fields]);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<FieldValues>({
    resolver: zodResolver(schema as never),
    defaultValues: toFormValues(fields, record as Record<string, unknown> | undefined),
  });

  // A select cannot take a value until its option exists, which is after the refreshed list has rendered.
  useEffect(() => {
    const waiting = added.current;
    const field = fields.find(({ name }) => name === waiting?.field);
    if (waiting && field?.options?.some((option) => option.value === waiting.id)) {
      setValue(waiting.field, waiting.id);
      added.current = null;
    }
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
                  {field.addPath && <AddOption field={field} onAdded={(id) => (added.current = { field: field.name, id })} />}
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
          {extra}
          <Button type="submit" disabled={isSubmitting}>
            Save
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
