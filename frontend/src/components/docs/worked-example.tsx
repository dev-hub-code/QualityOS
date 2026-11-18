import { CircleCheck, Lightbulb } from "lucide-react";
import type { WorkedExample as Example } from "@/lib/doc-examples";

/** A worked example: the situation, each step with what happens and why, and what to take away. */
export function WorkedExample({ example, label }: { example: Example; label?: string }) {
  return (
    <section aria-label={example.title} className="mt-6 rounded-2xl bg-muted/50 p-5 sm:p-6">
      {label && <p className="mb-1 text-sm font-medium text-primary">{label}</p>}
      <h3 className="text-xl font-semibold">{example.title}</h3>
      <p className="mt-3 max-w-prose text-muted-foreground">{example.scenario}</p>
      <ol className="mt-5 space-y-5">
        {example.steps.map((step, position) => (
          <li key={step.action} className="flex gap-3">
            <span
              aria-hidden
              className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
            >
              {position + 1}
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-xs font-semibold uppercase tracking-wide text-primary">{step.who}</p>
              <p className="mt-0.5 font-medium">{step.action}</p>
              <p className="mt-2 flex gap-2 text-sm">
                <CircleCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                <span>
                  <span className="font-medium">What happens: </span>
                  {step.result}
                </span>
              </p>
              <p className="mt-2 flex gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-950">
                <Lightbulb aria-hidden className="mt-0.5 size-4 shrink-0 text-amber-600" />
                <span>
                  <span className="font-medium">Why: </span>
                  {step.why}
                </span>
              </p>
            </div>
          </li>
        ))}
      </ol>
      <div className="mt-6 rounded-xl border bg-background/70 p-4">
        <h4 className="font-semibold">Key points</h4>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
          {example.takeaways.map((takeaway) => (
            <li key={takeaway}>{takeaway}</li>
          ))}
        </ul>
      </div>
    </section>
  );
}
