"use client";

import { useMe } from "@/hooks/use-me";

export default function DashboardPage() {
  const { data: me } = useMe();

  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold tracking-tight">Welcome, {me?.user.fullName}</h1>
      <p className="text-muted-foreground">
        Quality data will appear here as your team starts using QualityOS.
      </p>
    </div>
  );
}
