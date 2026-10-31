"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/components/billing/subscription-banner";
import { REASON_FIELD } from "@/components/platform/billing-dialogs";
import { PageHeader, QueryState, RecordLink, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { PlatformOperator } from "@/lib/types";

const ROLES = [
  { value: "SUPER_ADMIN", label: "Super Admin: everything, including operators" },
  { value: "BILLING_ADMIN", label: "Billing Admin: plans, features, invoices, payments" },
  { value: "SUPPORT_ADMIN", label: "Support Admin: suspend, reactivate, extend, retry email" },
  { value: "READ_ONLY_AUDITOR", label: "Read-only Auditor: reads everything, changes nothing" },
];
const ROLE_FIELD: FieldDef = { name: "role", label: "Platform role", type: "select", required: true, options: ROLES };
const ADD_FIELDS: FieldDef[] = [
  { name: "email", label: "Email of an existing, confirmed account", required: true },
  ROLE_FIELD,
  REASON_FIELD,
];
const CHANGE_FIELDS: FieldDef[] = [ROLE_FIELD, REASON_FIELD];
const ROLE_NAME: Record<string, string> = {
  SUPER_ADMIN: "Super Admin",
  BILLING_ADMIN: "Billing Admin",
  SUPPORT_ADMIN: "Support Admin",
  READ_ONLY_AUDITOR: "Read-only Auditor",
};

type Change = { operator: PlatformOperator; active: boolean; role?: boolean };

export default function PlatformOperatorsPage() {
  const refresh = useRefresh();
  const { data: me } = useMe();
  const manage = me?.permissions.includes("PLATFORM_OPERATOR_MANAGE") ?? false;
  const [adding, setAdding] = useState(false);
  const [change, setChange] = useState<Change | null>(null);
  const { data, error } = useQuery({
    queryKey: ["/platform/operators"],
    queryFn: () => api.get<PlatformOperator[]>("/platform/operators"),
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Operators"
        subtitle="Who can use the Control Center. Being an organisation's administrator gives no access here."
      >
        {manage && <Button onClick={() => setAdding(true)}>Add operator</Button>}
      </PageHeader>
      {!data ? (
        <QueryState error={error} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Operator</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Added</TableHead>
                <TableHead>Last action</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((operator) => (
                <TableRow key={operator.userId}>
                  <TableCell>
                    <div className="font-medium">{operator.fullName}</div>
                    <div className="text-sm text-muted-foreground">{operator.email}</div>
                  </TableCell>
                  <TableCell>{ROLE_NAME[operator.role] ?? operator.role}</TableCell>
                  <TableCell>
                    <StatusBadge status={operator.deactivatedAt ? "DEACTIVATED" : "ACTIVE"} />
                  </TableCell>
                  <TableCell>
                    {formatDate(operator.addedAt)}
                    {operator.addedBy ? ` by ${operator.addedBy}` : ""}
                  </TableCell>
                  <TableCell>{operator.lastActivity ? formatDate(operator.lastActivity) : "None"}</TableCell>
                  <TableCell className="space-x-2 text-right whitespace-nowrap">
                    <RecordLink href={`/platform/audit-logs?q=${encodeURIComponent(operator.email)}`}>Activity</RecordLink>
                    {manage && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => setChange({ operator, active: true, role: true })}>
                          Change role
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setChange({ operator, active: !!operator.deactivatedAt })}
                        >
                          {operator.deactivatedAt ? "Reactivate" : "Deactivate"}
                        </Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {adding && (
        <RecordDialog
          title="Add operator"
          fields={ADD_FIELDS}
          record={{ role: "READ_ONLY_AUDITOR" }}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            await api.post("/platform/operators", values);
            await refresh();
          }}
        />
      )}
      {change && (
        <RecordDialog
          title={
            change.role
              ? `Change role of ${change.operator.email}`
              : `${change.active ? "Reactivate" : "Deactivate"} ${change.operator.email}`
          }
          fields={change.role ? CHANGE_FIELDS : [REASON_FIELD]}
          record={{ role: change.operator.role }}
          onClose={() => setChange(null)}
          onSubmit={async ({ reason, role }) => {
            await api.put(`/platform/operators/${change.operator.userId}`, {
              reason,
              role: role ?? change.operator.role,
              active: change.role ? !change.operator.deactivatedAt : change.active,
            });
            await refresh();
          }}
        />
      )}
    </div>
  );
}
