"use client";

import { useParams } from "next/navigation";
import { BomPage } from "@/components/manufacturing/bom-page";

export default function Page() {
  const { id } = useParams<{ id: string }>();
  return <BomPage kind="BOM" id={id} />;
}
