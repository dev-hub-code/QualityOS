"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useMe } from "@/hooks/use-me";
import { landingPage } from "@/lib/navigation";

/** Where a session begins. Only the destination depends on the account: the backend still decides what it can do. */
export default function StartPage() {
  const { data: me, isError } = useMe();
  const router = useRouter();

  useEffect(() => {
    if (me) router.replace(landingPage(me.permissions));
    else if (isError) router.replace("/dashboard");
  }, [me, isError, router]);

  return <p className="p-6 text-sm text-muted-foreground">Loading…</p>;
}
