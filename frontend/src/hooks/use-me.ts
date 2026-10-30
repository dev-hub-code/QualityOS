"use client";

import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { Me } from "@/lib/types";

export function useMe() {
  return useQuery({ queryKey: ["me"], queryFn: () => api.get<Me>("/auth/me") });
}

export function usePermission(permission: string): boolean {
  return useMe().data?.permissions.includes(permission) ?? false;
}
