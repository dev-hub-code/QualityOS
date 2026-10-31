import { ArrowDown, CornerDownRight } from "lucide-react";
import type { Flow } from "@/lib/doc-flows";

export function FlowChart({ flow }: { flow: Flow }) {
  return (
    <figure aria-label={flow.title} className="rounded-lg border p-4 sm:p-5">
      <figcaption className="font-semibold">{flow.title}</figcaption>
      <ol className="mt-4">
        {flow.steps.map((step, index) => (
          <li
            key={step.label}
            className="grid gap-x-6 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] sm:grid-rows-[auto_1fr]"
          >
            <div className={`rounded-md border px-3 py-2 ${step.branches ? "border-primary bg-primary/5" : "bg-muted/40"}`}>
              <p className="text-sm font-medium">{step.label}</p>
              {step.note && <p className="text-sm text-muted-foreground">{step.note}</p>}
            </div>
            {step.branches && (
              <ul className="mt-2 space-y-2 sm:col-start-2 sm:row-span-2 sm:row-start-1 sm:mt-0 sm:pb-3">
                {step.branches.map((branch) => (
                  <li key={branch.when} className="flex gap-2 text-sm">
                    <CornerDownRight aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    <p className="min-w-0">
                      <span className="font-medium">{branch.when}: </span>
                      <span className="text-muted-foreground">{branch.then}</span>
                    </p>
                  </li>
                ))}
              </ul>
            )}
            {index < flow.steps.length - 1 && (
              <div aria-hidden className="flex flex-col items-center py-1 text-muted-foreground sm:col-start-1 sm:row-start-2">
                <span className="min-h-1 w-px flex-1 bg-border" />
                <ArrowDown className="-mt-1.5 size-4" />
              </div>
            )}
          </li>
        ))}
      </ol>
    </figure>
  );
}
