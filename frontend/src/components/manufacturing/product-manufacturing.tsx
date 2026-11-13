"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { DEFINITIONS, type DefinitionKind } from "@/lib/manufacturing";
import type { ProductManufacturingSettings, Revision } from "@/lib/types";

const KIND_LABELS: Record<DefinitionKind, string> = { BOM: "BOM", RECIPE: "Recipe", ROUTING: "Routing" };
const MODELS = { DISCRETE: "Discrete – built from a BOM", PROCESS: "Process – made to a recipe" };

/** How a product is made, and its BOM, recipe and routing revisions. */
export function ProductManufacturing({ productId }: { productId: string }) {
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const refresh = useRefresh();
  const [choosing, setChoosing] = useState(false);
  const settingsPath = `/manufacturing/products/${productId}/settings`;
  const { data: settings } = useQuery({
    queryKey: [settingsPath],
    queryFn: () => api.get<ProductManufacturingSettings>(settingsPath),
  });
  const kinds: DefinitionKind[] = [settings?.manufacturingModel === "PROCESS" ? "RECIPE" : "BOM", "ROUTING"];
  const revisions = useQuery({
    queryKey: ["/manufacturing/product-revisions", productId, kinds.join()],
    queryFn: async () => {
      const pages = await Promise.all(
        kinds.map((kind) => api.get<PageResponse<Revision>>(`${DEFINITIONS[kind].path}?productId=${productId}&size=20`)),
      );
      return pages.flatMap((page) => page.items);
    },
    enabled: Boolean(settings),
  });
  if (!settings) return null;
  return (
    <Section
      title="Manufacturing"
      action={
        canManage && (
          <Button variant="outline" size="sm" onClick={() => setChoosing(true)}>
            Set how it is made
          </Button>
        )
      }
    >
      <p className="text-sm">
        {settings.manufacturingModel ? MODELS[settings.manufacturingModel] : "Not chosen yet. Your organisation makes both kinds."}
        {settings.manufacturingModel && !settings.chosen && <span className="text-muted-foreground"> (your organisation&apos;s model)</span>}
      </p>
      <ul className="mt-3 divide-y text-sm">
        {(revisions.data ?? []).map((revision) => (
          <li key={revision.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
            <Link className="text-primary hover:underline" href={`${DEFINITIONS[revision.kind].path}/${revision.id}`}>
              {KIND_LABELS[revision.kind]} revision {revision.revision}
            </Link>
            <span className="flex items-center gap-2">
              {revision.inEffect && <StatusBadge status="IN_EFFECT" />}
              <StatusBadge status={revision.status} />
            </span>
          </li>
        ))}
      </ul>
      {choosing && (
        <RecordDialog
          title="How this product is made"
          fields={[
            {
              name: "manufacturingModel",
              label: "Manufacturing model",
              type: "select",
              required: true,
              options: Object.entries(MODELS).map(([value, label]) => ({ value, label })),
            },
          ]}
          record={settings}
          onClose={() => setChoosing(false)}
          onSubmit={(values) => api.put(settingsPath, values).then(refresh)}
        />
      )}
    </Section>
  );
}
