"use client";

import { Suspense } from "react";
import { PageHeader } from "@/components/records/parts";
import { SerialSearch } from "@/components/traceability/serial-search";

export default function SerialSearchPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Serial number search" />
      <Suspense>
        <SerialSearch />
      </Suspense>
    </div>
  );
}
