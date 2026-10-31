"use client";

import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Section, useAction } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { FISHBONE_BRANCHES, WHY_COUNT, toFishbone, toFiveWhys } from "@/lib/capa";
import type { Capa } from "@/lib/types";

const TEXTAREA_CLASS = "w-full rounded-md border border-input bg-transparent px-2 py-1.5 text-sm";

/** The 5 Why and fishbone tools are optional aids. Only the root cause itself is required. */
export function CapaAnalysisForm({ capa }: { capa: Capa }) {
  const { run, pending, message } = useAction();
  const [rootCause, setRootCause] = useState(capa.rootCause ?? "");
  const [whys, setWhys] = useState(() => Array.from({ length: WHY_COUNT }, (_, index) => capa.fiveWhys[index] ?? ""));
  const [branches, setBranches] = useState(() =>
    Object.fromEntries(FISHBONE_BRANCHES.map((branch) => [branch, (capa.fishbone[branch] ?? []).join("\n")])),
  );
  const path = `/capas/${capa.id}`;
  const save = () =>
    api.put(`${path}/analysis`, { rootCause, fiveWhys: toFiveWhys(whys), fishbone: toFishbone(branches) });

  return (
    <Section title="Root cause analysis">
      <div className="space-y-5">
        <FormError message={message} />
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">5 Why</legend>
          {whys.map((why, index) => (
            <div key={index} className="space-y-1">
              <Label htmlFor={`why-${index}`} className="font-normal">
                Why {index + 1}
              </Label>
              <Input
                id={`why-${index}`}
                value={why}
                onChange={(event) =>
                  setWhys(whys.map((value, position) => (position === index ? event.target.value : value)))
                }
              />
            </div>
          ))}
        </fieldset>
        <fieldset>
          <legend className="mb-2 text-sm font-medium">Fishbone (one cause per line)</legend>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {FISHBONE_BRANCHES.map((branch) => (
              <div key={branch} className="space-y-1">
                <Label htmlFor={`branch-${branch}`} className="font-normal">
                  {branch}
                </Label>
                <textarea
                  id={`branch-${branch}`}
                  rows={3}
                  className={TEXTAREA_CLASS}
                  value={branches[branch]}
                  onChange={(event) => setBranches({ ...branches, [branch]: event.target.value })}
                />
              </div>
            ))}
          </div>
        </fieldset>
        <div className="space-y-1">
          <Label htmlFor="root-cause">Root cause</Label>
          <textarea
            id="root-cause"
            rows={3}
            className={TEXTAREA_CLASS}
            value={rootCause}
            onChange={(event) => setRootCause(event.target.value)}
          />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={pending} onClick={() => run(save)}>
            Save analysis
          </Button>
          <Button
            disabled={pending}
            onClick={() => run(() => save().then(() => api.post(`${path}/finish-analysis`)))}
          >
            Finish analysis
          </Button>
        </div>
      </div>
    </Section>
  );
}
