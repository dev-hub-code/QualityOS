"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { REASON_FIELD } from "@/components/platform/billing-dialogs";
import { PageHeader, QueryState, Section, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Feature, FeatureOrganisationList, Plan } from "@/lib/types";

const path = "/platform/features";

const DETAILS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "description", label: "Description", type: "textarea" },
  { name: "defaultEnabled", label: "Included on new plans by default", type: "checkbox" },
];

const NEW_FEATURE: FieldDef[] = [
  { name: "code", label: "Code, such as PRIORITY_SUPPORT", required: true },
  ...DETAILS,
  {
    name: "valueType",
    label: "Value",
    type: "select",
    required: true,
    options: [
      { value: "BOOLEAN", label: "On or off" },
      { value: "NUMBER", label: "A number" },
      { value: "TEXT", label: "Text" },
      { value: "STRUCTURED", label: "Structured (JSON)" },
    ],
  },
];

const VALUE_HELP = { BOOLEAN: "", NUMBER: "Number (blank for unlimited)", TEXT: "Text", STRUCTURED: "JSON" };

/** Text from the form becomes the type the feature holds. Bad JSON is reported in the form. */
function parsed(feature: Feature, value: unknown) {
  if (value === undefined || value === null || value === "") return null;
  if (feature.valueType === "NUMBER") return Number(value);
  if (feature.valueType === "STRUCTURED") return JSON.parse(String(value));
  return value;
}

interface Assigning {
  feature: Feature;
  plan: Plan;
  included: boolean;
}

function Organisations({ feature, onClose }: { feature: Feature; onClose: () => void }) {
  const list = `${path}/${feature.code}/organisations?size=100`;
  const { data, error } = useQuery({ queryKey: [list], queryFn: () => api.get<FeatureOrganisationList>(list) });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Organisations with {feature.name}</DialogTitle>
        </DialogHeader>
        {!data ? (
          <QueryState error={error} />
        ) : data.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">None yet.</p>
        ) : (
          <>
            {data.total > data.items.length && (
              <p className="text-sm text-muted-foreground">
                Showing {data.items.length} of {data.total}.
              </p>
            )}
            <ul className="max-h-96 space-y-1 overflow-auto text-sm">
              {data.items.map(({ tenantId, name, planName }) => (
                <li key={tenantId}>
                  <Link className="underline" href={`/platform/organisations/${tenantId}`}>
                    {name}
                  </Link>{" "}
                  ({planName})
                </li>
              ))}
            </ul>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function PlatformFeaturesPage() {
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Feature | null>(null);
  const [toggling, setToggling] = useState<Feature | null>(null);
  const [viewing, setViewing] = useState<Feature | null>(null);
  const [assigning, setAssigning] = useState<Assigning | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Feature[]>(path) });
  const plans = useQuery({ queryKey: ["/platform/plans"], queryFn: () => api.get<Plan[]>("/platform/plans") });
  if (!data) return <QueryState error={error} />;
  const listed = (plans.data ?? []).filter((plan) => !plan.archivedAt);
  const assignable = data.filter(({ code, active }) => active || listed.some((plan) => plan.features.includes(code)));
  const assignFields: FieldDef[] = assigning
    ? [
        ...(assigning.included && assigning.feature.valueType !== "BOOLEAN"
          ? [{ name: "value", label: VALUE_HELP[assigning.feature.valueType] }]
          : []),
        { ...REASON_FIELD, required: false, label: "Reason (needed if organisations are on this plan)" },
      ]
    : [];
  return (
    <div className="space-y-6">
      <PageHeader
        title="Features"
        subtitle="A plan decides what an organisation has bought. A person's role still decides what they may do."
      >
        <Button onClick={() => setAdding(true)}>New feature</Button>
      </PageHeader>
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Feature</TableHead>
              <TableHead>Value</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Enforcement</TableHead>
              <TableHead>Organisations</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((feature) => (
              <TableRow key={feature.code}>
                <TableCell className="whitespace-normal">
                  {feature.name}
                  <div className="text-xs text-muted-foreground">
                    {feature.code}
                    {feature.defaultEnabled && ", on new plans by default"}
                  </div>
                </TableCell>
                <TableCell>{feature.valueType.toLowerCase()}</TableCell>
                <TableCell>{feature.active ? "Active" : "Inactive"}</TableCell>
                <TableCell>{feature.enforced ? "Enforced" : "Listed only, not enforced"}</TableCell>
                <TableCell>{feature.organisations}</TableCell>
                <TableCell className="space-x-2 whitespace-nowrap">
                  <Button size="sm" variant="outline" onClick={() => setEditing(feature)}>
                    Edit
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setToggling(feature)}>
                    {feature.active ? "Deactivate" : "Activate"}
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setViewing(feature)}>
                    Organisations
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Section title="Features by plan">
        <p className="mb-3 text-sm text-muted-foreground">
          Compare plans here, and select a cell to change it. A change reaches that plan&apos;s organisations at once.
        </p>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Feature</TableHead>
                {listed.map((plan) => (
                  <TableHead key={plan.id}>{plan.name}</TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {assignable.map((feature) => (
                <TableRow key={feature.code}>
                  <TableCell className="whitespace-normal">{feature.name}</TableCell>
                  {listed.map((plan) => {
                    const included = plan.features.includes(feature.code);
                    const value = plan.featureValues[feature.code];
                    return (
                      <TableCell key={plan.id}>
                        <Button
                          size="sm"
                          variant={included ? "default" : "outline"}
                          aria-label={`${feature.name} on ${plan.name}: ${included ? "included" : "not included"}`}
                          onClick={() => setAssigning({ feature, plan, included: !included })}
                        >
                          {included ? (value === undefined ? "Included" : JSON.stringify(value)) : "Not included"}
                        </Button>
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>
      {adding && (
        <RecordDialog
          title="New feature"
          fields={NEW_FEATURE}
          record={{ valueType: "BOOLEAN" }}
          extra={
            <p className="text-sm text-muted-foreground">
              A new feature is listed on plans but restricts nothing until the product enforces it.
            </p>
          }
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            await api.post(path, values);
            await refresh();
          }}
        />
      )}
      {editing && (
        <RecordDialog
          title={`Edit ${editing.name}`}
          fields={DETAILS}
          record={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            await api.put(`${path}/${editing.code}`, { ...values, version: editing.version });
            await refresh();
          }}
        />
      )}
      {toggling && (
        <RecordDialog
          title={`${toggling.active ? "Deactivate" : "Activate"} ${toggling.name}`}
          fields={[REASON_FIELD]}
          extra={
            <p className="text-sm text-muted-foreground">
              An inactive feature cannot be added to a plan. Plans that have it keep it.
            </p>
          }
          onClose={() => setToggling(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${toggling.code}/active`, {
              ...values,
              active: !toggling.active,
              version: toggling.version,
            });
            await refresh();
          }}
        />
      )}
      {assigning && (
        <RecordDialog
          title={`${assigning.included ? "Add" : "Remove"} ${assigning.feature.name} ${assigning.included ? "to" : "from"} ${assigning.plan.name}`}
          fields={assignFields}
          onClose={() => setAssigning(null)}
          onSubmit={async ({ value, reason }) => {
            await api.post(`${path}/${assigning.feature.code}/plans`, {
              planId: assigning.plan.id,
              included: assigning.included,
              value: parsed(assigning.feature, value),
              version: assigning.plan.version,
              reason,
            });
            await refresh();
          }}
        />
      )}
      {viewing && <Organisations feature={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}
