"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { useFacilities } from "@/components/records/parts";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useMe } from "@/hooks/use-me";
import { ApiError, api, type PageResponse } from "@/lib/api";
import { inviteSchema, type InviteValues } from "@/lib/schemas";
import type { Invitation, Member, Role } from "@/lib/types";

function RolePicker({
  roles,
  selected,
  onChange,
}: {
  roles: Role[];
  selected: Set<string>;
  onChange: (next: Set<string>) => void;
}) {
  return (
    <fieldset className="max-h-56 space-y-2 overflow-y-auto">
      <legend className="mb-2 text-sm font-medium">Roles</legend>
      {roles.map((role) => (
        <div key={role.id} className="flex items-center gap-2">
          <Checkbox
            id={`role-${role.id}`}
            checked={selected.has(role.id)}
            onCheckedChange={(checked) => {
              const next = new Set(selected);
              if (checked) next.add(role.id);
              else next.delete(role.id);
              onChange(next);
            }}
          />
          <Label htmlFor={`role-${role.id}`} className="font-normal">
            {role.name}
          </Label>
        </div>
      ))}
    </fieldset>
  );
}

function AddMemberDialog({ roles, onClose }: { roles: Role[]; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState(new Set<string>());
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<InviteValues>({ resolver: zodResolver(inviteSchema) });

  async function onSubmit(values: InviteValues) {
    if (selected.size === 0) {
      setFormError("Choose at least one role.");
      return;
    }
    setFormError("");
    try {
      await api.post("/invitations", { email: values.email, roleIds: [...selected] });
      await queryClient.invalidateQueries({ queryKey: ["invitations"] });
      onClose();
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Invite user</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
          <FormError message={formError} />
          <Field label="Email" id="email" type="email" error={errors.email} {...register("email")} />
          <p className="text-sm text-muted-foreground">
            They will get a link by email, valid for seven days, and choose their own password.
          </p>
          <RolePicker roles={roles} selected={selected} onChange={setSelected} />
          <Button type="submit" disabled={isSubmitting}>
            Send invitation
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function FacilitiesDialog({ member, onClose }: { member: Member; onClose: () => void }) {
  const queryClient = useQueryClient();
  const facilities = useFacilities();
  const [selected, setSelected] = useState(new Set(member.facilityIds));
  const [formError, setFormError] = useState("");
  async function save() {
    try {
      await api.put(`/users/${member.membershipId}/facilities`, { facilityIds: [...selected] });
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      onClose();
    } catch (error) {
      setFormError(error instanceof ApiError ? error.message : "Could not save.");
    }
  }
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Facilities for {member.fullName}</DialogTitle>
        </DialogHeader>
        <FormError message={formError} />
        <p className="text-sm text-muted-foreground">
          Choose none to allow every facility. This limits batches, traces, serial numbers and recalls.
        </p>
        <fieldset className="max-h-56 space-y-2 overflow-y-auto">
          <legend className="sr-only">Facilities</legend>
          {facilities.map((facility) => (
            <div key={facility.value} className="flex items-center gap-2">
              <Checkbox
                id={`facility-${facility.value}`}
                checked={selected.has(facility.value)}
                onCheckedChange={(checked) => {
                  const next = new Set(selected);
                  if (checked) next.add(facility.value);
                  else next.delete(facility.value);
                  setSelected(next);
                }}
              />
              <Label htmlFor={`facility-${facility.value}`} className="font-normal">
                {facility.label}
              </Label>
            </div>
          ))}
        </fieldset>
        <Button onClick={save}>Save</Button>
      </DialogContent>
    </Dialog>
  );
}

function EditRolesDialog({
  member,
  roles,
  onClose,
}: {
  member: Member;
  roles: Role[];
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState(new Set(member.roles.map((role) => role.id)));
  const save = useMutation({
    mutationFn: () => api.put(`/users/${member.membershipId}/roles`, { roleIds: [...selected] }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      onClose();
    },
  });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Roles for {member.fullName}</DialogTitle>
        </DialogHeader>
        <FormError message={save.error?.message} />
        <RolePicker roles={roles} selected={selected} onChange={setSelected} />
        <Button disabled={save.isPending || selected.size === 0} onClick={() => save.mutate()}>
          Save
        </Button>
      </DialogContent>
    </Dialog>
  );
}

function PendingInvitations() {
  const queryClient = useQueryClient();
  const invitations = useQuery({
    queryKey: ["invitations"],
    queryFn: () => api.get<Invitation[]>("/invitations"),
  });
  const act = useMutation({
    mutationFn: ({ id, action }: { id: string; action: "resend" | "revoke" }) =>
      api.post(`/invitations/${id}/${action}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["invitations"] }),
  });
  if (!invitations.data?.length) return null;
  return (
    <section className="space-y-2">
      <h2 className="font-medium">Pending invitations</h2>
      <FormError message={act.error?.message} />
      <ul className="divide-y rounded-lg border text-sm">
        {invitations.data.map((invitation) => (
          <li key={invitation.id} className="flex flex-wrap items-center justify-between gap-2 p-3">
            <span>
              {invitation.email}
              {invitation.expired && <Badge variant="destructive" className="ml-2">Expired</Badge>}
            </span>
            <span>
              <Button variant="ghost" size="sm" onClick={() => act.mutate({ id: invitation.id, action: "resend" })}>
                Resend
              </Button>
              <Button variant="ghost" size="sm" onClick={() => act.mutate({ id: invitation.id, action: "revoke" })}>
                Revoke
              </Button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default function UsersPage() {
  const queryClient = useQueryClient();
  const { data: me } = useMe();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Member | null>(null);
  const [placing, setPlacing] = useState<Member | null>(null);
  const users = useQuery({
    queryKey: ["users"],
    queryFn: () => api.get<PageResponse<Member>>("/users?size=100"),
  });
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.get<Role[]>("/roles") });
  const setActive = useMutation({
    mutationFn: ({ member, active }: { member: Member; active: boolean }) =>
      api.post(`/users/${member.membershipId}/${active ? "activate" : "deactivate"}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["users"] }),
  });

  if (users.error instanceof ApiError && users.error.status === 403) {
    return <p>You do not have permission to manage users.</p>;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">Users</h1>
        <Button onClick={() => setAdding(true)} disabled={!roles.data}>
          Invite user
        </Button>
      </div>
      <FormError message={setActive.error?.message} />
      <PendingInvitations />
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Email</TableHead>
              <TableHead>Roles</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>
                <span className="sr-only">Actions</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.data?.items.map((member) => (
              <TableRow key={member.membershipId}>
                <TableCell>{member.fullName}</TableCell>
                <TableCell>{member.email}</TableCell>
                <TableCell>
                  <div className="flex flex-wrap gap-1">
                    {member.roles.map((role) => (
                      <Badge key={role.id} variant="secondary">
                        {role.name}
                      </Badge>
                    ))}
                  </div>
                </TableCell>
                <TableCell>
                  <Badge variant={member.status === "ACTIVE" ? "outline" : "destructive"}>
                    {member.status === "ACTIVE" ? "Active" : "Inactive"}
                  </Badge>
                </TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => setEditing(member)}>
                    Edit roles
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setPlacing(member)}>
                    Facilities
                  </Button>
                  {member.userId !== me?.user.id && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setActive.mutate({ member, active: member.status !== "ACTIVE" })}
                    >
                      {member.status === "ACTIVE" ? "Deactivate" : "Activate"}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {adding && roles.data && <AddMemberDialog roles={roles.data} onClose={() => setAdding(false)} />}
      {placing && <FacilitiesDialog member={placing} onClose={() => setPlacing(null)} />}
      {editing && roles.data && (
        <EditRolesDialog member={editing} roles={roles.data} onClose={() => setEditing(null)} />
      )}
    </div>
  );
}
