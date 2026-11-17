"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/components/billing/subscription-banner";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { api, hardNavigate, session } from "@/lib/api";

/**
 * The Control Center's way into an organisation's records: only while the organisation has granted support access,
 * read-only unless it also allowed changes, and with a reason the organisation will see.
 */
export function SupportSessionControl({ organisationId, canOpen }: { organisationId: string; canOpen: boolean }) {
  const path = `/platform/organisations/${organisationId}/support-access`;
  const { data } = useQuery({ queryKey: [path], queryFn: () => api.get<{ openUntil: string | null; allowChanges: boolean }>(path) });
  const [opening, setOpening] = useState(false);
  if (!data) return null;
  return (
    <>
      <p className="text-sm text-muted-foreground">
        {data.openUntil
          ? `Support access granted until ${formatDate(data.openUntil)}: ${data.allowChanges ? "view and make changes" : "view only"}.`
          : "Support access not granted. Only the organisation's administrator can grant it, under Settings."}
      </p>
      {canOpen && data.openUntil && (
        <Button variant="outline" onClick={() => setOpening(true)}>
          Open support view
        </Button>
      )}
      {opening && (
        <RecordDialog
          title={data.allowChanges ? "Open a support session that can make changes" : "Open a read-only support view"}
          fields={[{ name: "reason", label: "Why you need to look (the organisation sees this)", type: "textarea", required: true }]}
          onClose={() => setOpening(false)}
          onSubmit={async (values) => {
            await session.post("support-session", { tenantId: organisationId, reason: values.reason });
            hardNavigate("/dashboard");
          }}
        />
      )}
    </>
  );
}
