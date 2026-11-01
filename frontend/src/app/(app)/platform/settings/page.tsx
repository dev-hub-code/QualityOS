"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { REASON_FIELD } from "@/components/platform/billing-dialogs";
import { Details, PageHeader, QueryState, Section, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { PlatformSettings } from "@/lib/types";

const PATH = "/platform/settings";
const FIELDS: FieldDef[] = [
  { name: "defaultTrialDays", label: "Trial days for new organisations", type: "decimal", required: true },
  { name: "defaultGraceDays", label: "Default grace days for new plans", type: "decimal", required: true },
  { name: "supportEmail", label: "Support email shown to customers", type: "email" },
  { name: "registrationOpen", label: "Allow new sign-ups", type: "checkbox" },
  { name: "maintenanceMode", label: "Maintenance mode: customers can read but not change records", type: "checkbox" },
  REASON_FIELD,
];

export default function PlatformSettingsPage() {
  const refresh = useRefresh();
  const manage = usePermission("PLATFORM_SETTINGS_MANAGE");
  const [editing, setEditing] = useState(false);
  const { data, error } = useQuery({ queryKey: [PATH], queryFn: () => api.get<PlatformSettings>(PATH) });
  return (
    <div className="space-y-6">
      <PageHeader title="Platform settings" subtitle="Customers never see this page. Secrets stay in the environment, not here.">
        {manage && data && <Button onClick={() => setEditing(true)}>Edit settings</Button>}
      </PageHeader>
      {!data ? (
        <QueryState error={error} />
      ) : (
        <Section title="Current settings">
          <Details
            items={[
              ["Trial days for new organisations", data.defaultTrialDays],
              ["Default grace days", data.defaultGraceDays],
              ["Support email", data.supportEmail ?? "Not set"],
              ["New sign-ups", data.registrationOpen ? "Open" : "Closed"],
              ["Maintenance mode", data.maintenanceMode ? "On" : "Off"],
            ]}
          />
        </Section>
      )}
      {editing && data && (
        <RecordDialog
          title="Edit settings"
          fields={FIELDS}
          record={{ ...data, defaultTrialDays: String(data.defaultTrialDays), defaultGraceDays: String(data.defaultGraceDays) }}
          onClose={() => setEditing(false)}
          onSubmit={async (values) => {
            await api.put(PATH, {
              ...values,
              defaultTrialDays: Number(values.defaultTrialDays),
              defaultGraceDays: Number(values.defaultGraceDays),
              version: data.version,
            });
            await refresh();
          }}
        />
      )}
    </div>
  );
}
