"use client";

import { useParams, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { SerialDetail } from "@/components/traceability/serial-detail";

function Detail() {
  const { serial } = useParams<{ serial: string }>();
  const from = useSearchParams().get("from");
  // Only ever a query string, so this link cannot lead anywhere but the search page.
  return (
    <SerialDetail serial={decodeURIComponent(serial)} backHref={`/traceability/serials${from ? `?${from}` : ""}`} />
  );
}

export default function SerialTracePage() {
  return (
    <Suspense>
      <Detail />
    </Suspense>
  );
}
