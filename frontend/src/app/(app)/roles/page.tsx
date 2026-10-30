"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { roleSchema, type RoleValues } from "@/lib/schemas";
import type { Permission, Role } from "@/lib/types";

function RoleDialog({
  role,
  permissions,
  onClose,
}: {
  role: Role | null;
  permissions: Permission[];
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState(new Set(role?.permissions ?? []));
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RoleValues>({
    resolver: zodResolver(roleSchema),
    defaultValues: { name: role?.name ?? "", description: role?.description ?? "" },
  });

  const modules = useMemo(() => Object.entries(Object.groupBy(permissions, (p) => p.module)), [permissions]);

  async function onSubmit(values: RoleValues) {
    if (selected.size === 0) {
      setFormError("Choose at least one permission.");
      return;
    }
    setFormError("");
    const body = { ...values, permissions: [...selected] };
    try {
      await (role ? api.put(`/roles/${role.id}`, body) : api.post("/roles", body));
      await queryClient.invalidateQueries({ queryKey: ["roles"] });
      onClose();
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{role ? `Edit ${role.name}` : "New role"}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
          <FormError message={formError} />
          <Field label="Name" id="name" error={errors.name} {...register("name")} />
          <Field label="Description" id="description" error={errors.description} {...register("description")} />
          <div className="max-h-72 space-y-4 overflow-y-auto">
            {modules.map(([module, items]) => (
              <fieldset key={module} className="space-y-2">
                <legend className="text-sm font-medium">{module}</legend>
                {items?.map((permission) => (
                  <div key={permission.code} className="flex items-center gap-2">
                    <Checkbox
                      id={permission.code}
                      checked={selected.has(permission.code)}
                      onCheckedChange={(checked) => {
                        const next = new Set(selected);
                        if (checked) next.add(permission.code);
                        else next.delete(permission.code);
                        setSelected(next);
                      }}
                    />
                    <Label htmlFor={permission.code} className="font-normal">
                      {permission.description}
                    </Label>
                  </div>
                ))}
              </fieldset>
            ))}
          </div>
          <Button type="submit" disabled={isSubmitting}>
            Save role
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export default function RolesPage() {
  const queryClient = useQueryClient();
  const canManage = usePermission("ROLE_MANAGE");
  const [dialog, setDialog] = useState<{ role: Role | null } | null>(null);
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.get<Role[]>("/roles") });
  const permissions = useQuery({
    queryKey: ["permissions"],
    queryFn: () => api.get<Permission[]>("/permissions"),
  });
  const remove = useMutation({
    mutationFn: (id: string) => api.delete(`/roles/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["roles"] }),
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">Roles</h1>
        {canManage && <Button onClick={() => setDialog({ role: null })}>New role</Button>}
      </div>
      <FormError message={remove.error?.message} />
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Role</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Permissions</TableHead>
              <TableHead>
                <span className="sr-only">Actions</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {roles.data?.map((role) => (
              <TableRow key={role.id}>
                <TableCell>
                  <p>{role.name}</p>
                  <p className="text-sm text-muted-foreground">{role.description}</p>
                </TableCell>
                <TableCell>
                  <Badge variant={role.system ? "secondary" : "outline"}>
                    {role.system ? "System" : "Custom"}
                  </Badge>
                </TableCell>
                <TableCell>{role.permissions.length}</TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  {canManage && !role.system && (
                    <>
                      <Button variant="ghost" size="sm" onClick={() => setDialog({ role })}>
                        Edit
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => remove.mutate(role.id)}>
                        Delete
                      </Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {dialog && permissions.data && (
        <RoleDialog role={dialog.role} permissions={permissions.data} onClose={() => setDialog(null)} />
      )}
    </div>
  );
}
