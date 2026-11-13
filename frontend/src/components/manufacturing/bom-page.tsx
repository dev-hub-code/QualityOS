"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { RevisionHeader } from "@/components/manufacturing/revision-header";
import { QueryState, Section, useAction, useMaterials, useProducts, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { DEFINITIONS, describeLimits, toLineRequest } from "@/lib/manufacturing";
import { DECIMAL_PATTERN, formatQuantity, type FieldDef } from "@/lib/record-form";
import type { BomDetail, BomLine, Explosion, ScaledRecipe } from "@/lib/types";

const PARAMETER_FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "unit", label: "Unit, such as °C or bar" },
  { name: "target", label: "Target", type: "decimal" },
  { name: "minimum", label: "Lower limit", type: "decimal" },
  { name: "maximum", label: "Upper limit", type: "decimal" },
];

/** Asks for a quantity, then shows what the BOM or recipe needs for it. */
function QuantityTool({ label, onChange }: { label: string; onChange: (value: string) => void }) {
  const [value, setValue] = useState("");
  const valid = DECIMAL_PATTERN.test(value) && Number(value) > 0;
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (valid) onChange(value);
      }}
    >
      <label className="space-y-1 text-sm">
        <span className="block font-medium">{label}</span>
        <Input value={value} onChange={(event) => setValue(event.target.value)} inputMode="decimal" className="w-40" />
      </label>
      <Button type="submit" variant="outline" disabled={!valid}>
        Show
      </Button>
    </form>
  );
}

function Explode({ path, unit }: { path: string; unit: string }) {
  const units = useUnits();
  const [quantity, setQuantity] = useState("");
  const { data, error } = useQuery({
    queryKey: [path, "explosion", quantity],
    queryFn: () => api.get<Explosion>(`${path}/explosion?quantity=${quantity}`),
    enabled: quantity !== "",
  });
  return (
    <Section title="Material needed">
      <div className="space-y-4">
        <QuantityTool label={`Quantity to make (${unit})`} onChange={setQuantity} />
        <FormError message={error?.message} />
        {data && (
          <>
            <ul className="divide-y text-sm">
              {data.rows.map((row, index) => (
                <li key={index} className="flex justify-between gap-3 py-1.5" style={{ paddingLeft: `${(row.level - 1) * 1.25}rem` }}>
                  <span className={row.exploded ? "font-medium" : undefined}>
                    {row.componentCode} – {row.componentName}
                  </span>
                  <span>
                    {formatQuantity(row.quantity)} {units.label(row.uomId)}
                  </span>
                </li>
              ))}
            </ul>
            <div>
              <h3 className="mb-1 text-sm font-medium">In total, on {data.on}</h3>
              <ul className="divide-y text-sm">
                {data.totals.map((total) => (
                  <li key={total.componentId} className="flex justify-between gap-3 py-1.5">
                    <span>
                      {total.componentCode} – {total.componentName}
                    </span>
                    <span>
                      {formatQuantity(total.quantity)} {units.label(total.uomId)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </>
        )}
      </div>
    </Section>
  );
}

function Scale({ path, unit }: { path: string; unit: string }) {
  const units = useUnits();
  const [size, setSize] = useState("");
  const { data, error } = useQuery({
    queryKey: [path, "scaled", size],
    queryFn: () => api.get<ScaledRecipe>(`${path}/scaled?batchSize=${size}`),
    enabled: size !== "",
  });
  return (
    <Section title="Scale to a batch">
      <div className="space-y-3">
        <QuantityTool label={`Batch size (${unit})`} onChange={setSize} />
        <FormError message={error?.message} />
        {data && (
          <ul className="divide-y text-sm">
            {data.lines.map((line) => (
              <li key={line.lineNumber} className="flex justify-between gap-3 py-1.5">
                <span>
                  {line.componentCode} – {line.componentName}
                  {!line.scales && <span className="text-muted-foreground"> (fixed)</span>}
                </span>
                <span>
                  {formatQuantity(line.quantity)} {units.label(line.uomId)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Section>
  );
}

export function BomPage({ kind, id }: { kind: "BOM" | "RECIPE"; id: string }) {
  const definition = DEFINITIONS[kind];
  const path = `${definition.path}/${id}`;
  const canManage = usePermission(definition.manage);
  const units = useUnits();
  const materials = useMaterials();
  const products = useProducts();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [editing, setEditing] = useState<BomLine | "new" | null>(null);
  const [addingParameter, setAddingParameter] = useState(false);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<BomDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { revision, lines, parameters } = data;
  const unit = units.label(revision.productUomId);
  const draft = canManage && revision.status === "DRAFT";
  const lineFields: FieldDef[] = [
    {
      name: "component",
      label: "Material or product",
      type: "select",
      required: true,
      options: [
        ...materials.map((option) => ({ value: `material:${option.value}`, label: `Material: ${option.label}` })),
        ...products
          .filter((option) => option.value !== revision.productId)
          .map((option) => ({ value: `product:${option.value}`, label: `Product: ${option.label}` })),
      ],
    },
    { name: "quantity", label: `Quantity, for ${formatQuantity(revision.baseQuantity ?? "1")} ${unit}`, type: "decimal", required: true },
    { name: "uomId", label: "Unit", type: "select", required: true, options: units.options },
    { name: "scrapPercent", label: "Scrap allowance (%)", type: "decimal" },
    ...(kind === "RECIPE"
      ? ([{ name: "fixed", label: "Fixed quantity, whatever the batch size", type: "checkbox" }] as FieldDef[])
      : []),
    { name: "notes", label: "Notes", type: "textarea" },
  ];

  return (
    <div className="space-y-6">
      <RevisionHeader kind={kind} revision={revision} unit={unit} />
      <Section
        title={kind === "RECIPE" ? "Ingredients" : "Components"}
        action={
          draft && (
            <Button variant="outline" size="sm" onClick={() => setEditing("new")}>
              Add line
            </Button>
          )
        }
      >
        <FormError message={message} />
        {lines.length === 0 && <p className="text-sm text-muted-foreground">No lines yet.</p>}
        <ul className="divide-y text-sm">
          {lines.map((line) => (
            <li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                {line.lineNumber}. {line.componentCode} – {line.componentName}
                {line.componentType === "PRODUCT" && <span className="text-muted-foreground"> (made in-house)</span>}
              </span>
              <span className="flex items-center gap-2">
                <span>
                  {formatQuantity(line.quantity)} {units.label(line.uomId)}
                  {Number(line.scrapPercent) > 0 && <span className="text-muted-foreground"> + {formatQuantity(line.scrapPercent)}% scrap</span>}
                  {!line.scales && <span className="text-muted-foreground"> (fixed)</span>}
                </span>
                {draft && (
                  <>
                    <Button variant="ghost" size="sm" onClick={() => setEditing(line)}>
                      Edit
                    </Button>
                    <Button variant="ghost" size="sm" disabled={pending} onClick={() => run(() => api.delete(`${path}/lines/${line.id}`))}>
                      Remove
                    </Button>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {kind === "RECIPE" && (
        <Section
          title="Process parameters"
          action={
            draft && (
              <Button variant="outline" size="sm" onClick={() => setAddingParameter(true)}>
                Add parameter
              </Button>
            )
          }
        >
          {parameters.length === 0 && <p className="text-sm text-muted-foreground">None.</p>}
          <ul className="divide-y text-sm">
            {parameters.map((parameter) => (
              <li key={parameter.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  {parameter.code} – {parameter.name}
                </span>
                <span className="flex items-center gap-2">
                  {describeLimits(parameter)}
                  {draft && (
                    <Button variant="ghost" size="sm" disabled={pending} onClick={() => run(() => api.delete(`${path}/parameters/${parameter.id}`))}>
                      Remove
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {lines.length > 0 && (kind === "BOM" ? <Explode path={path} unit={unit} /> : <Scale path={path} unit={unit} />)}
      {editing && (
        <RecordDialog
          title={editing === "new" ? "Add line" : `Edit line ${editing.lineNumber}`}
          fields={lineFields}
          record={
            editing === "new"
              ? undefined
              : {
                  ...editing,
                  component: `${editing.componentType === "MATERIAL" ? "material" : "product"}:${editing.componentId}`,
                  fixed: !editing.scales,
                }
          }
          onClose={() => setEditing(null)}
          onSubmit={(values) =>
            (editing === "new"
              ? api.post(`${path}/lines`, toLineRequest(values))
              : api.put(`${path}/lines/${editing.id}`, toLineRequest(values))
            ).then(refresh)
          }
        />
      )}
      {addingParameter && (
        <RecordDialog
          title="Add process parameter"
          fields={PARAMETER_FIELDS}
          onClose={() => setAddingParameter(false)}
          onSubmit={(values) => api.post(`${path}/parameters`, values).then(refresh)}
        />
      )}
    </div>
  );
}
