"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Section, useAction } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import type { Inspection, Instrument } from "@/lib/types";

/** The list offers only instruments in date, and the server refuses any other. */
export function InspectionInstruments({ inspection, editable }: { inspection: Inspection; editable: boolean }) {
  const canExecute = usePermission("INSPECTION_EXECUTE");
  const { run, pending, message } = useAction();
  const [chosen, setChosen] = useState("");
  const { data } = useQuery({
    queryKey: ["/instruments/usable"],
    queryFn: () => api.get<PageResponse<Instrument>>("/instruments/usable"),
    enabled: editable && canExecute,
  });
  const used = new Set(inspection.instruments.map((instrument) => instrument.id));
  const options = (data?.items ?? []).filter((instrument) => !used.has(instrument.id));
  return (
    <Section title="Instruments used">
      <FormError message={message} />
      {inspection.instruments.length === 0 && <p className="text-sm text-muted-foreground">None recorded.</p>}
      <ul className="text-sm">
        {inspection.instruments.map((instrument) => (
          <li key={instrument.id}>
            {instrument.instrumentCode} – {instrument.name}, calibration due {instrument.nextDueOn}
          </li>
        ))}
      </ul>
      {editable && canExecute && (
        <div className="mt-2 flex flex-wrap gap-2">
          <select
            aria-label="Instrument"
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={chosen}
            onChange={(event) => setChosen(event.target.value)}
          >
            <option value="">Choose an instrument</option>
            {options.map((instrument) => (
              <option key={instrument.id} value={instrument.id}>
                {instrument.instrumentCode} – {instrument.name}
              </option>
            ))}
          </select>
          <Button
            variant="outline"
            disabled={!chosen || pending}
            onClick={() => {
              run(() => api.post(`/inspections/${inspection.id}/instruments`, { instrumentIds: [chosen] }));
              setChosen("");
            }}
          >
            Add instrument
          </Button>
        </div>
      )}
    </Section>
  );
}
