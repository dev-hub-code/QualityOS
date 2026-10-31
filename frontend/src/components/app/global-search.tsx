"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Input } from "@/components/ui/input";

export function GlobalSearch() {
  const router = useRouter();
  const [term, setTerm] = useState("");
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const query = term.trim();
    if (query) router.push(`/search?q=${encodeURIComponent(query)}`);
  };
  return (
    <form role="search" onSubmit={submit}>
      <Input
        aria-label="Search everything"
        placeholder="Serial, batch, lot, product"
        value={term}
        onChange={(event) => setTerm(event.target.value)}
      />
    </form>
  );
}
