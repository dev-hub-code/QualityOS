import { ArrowDown, CornerDownRight, Download } from "lucide-react";
import type { Flow } from "@/lib/doc-flows";
import { flowImage } from "@/lib/flow-svg";

/**
 * A workflow as a flowchart image on wider screens and in print, with a link to download it. On a phone, where the
 * image would be too small to read, the same steps show as a list; screen readers always get the list.
 */
export function FlowChart({ flow, fileName }: { flow: Flow; fileName?: string }) {
  const image = flowImage(flow);
  return (
    <figure aria-label={flow.title} className="glass rounded-2xl p-4 sm:p-3">
      {/* A generated SVG: there is nothing for the image optimiser to do. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={image} alt="" aria-hidden className="hidden h-auto w-full rounded-xl bg-white sm:block print:block" />
      {fileName && (
        <a
          href={image}
          download={`${fileName}.svg`}
          className="mt-2 hidden items-center gap-1.5 text-sm text-primary hover:underline sm:inline-flex print:hidden"
        >
          <Download aria-hidden className="size-4" />
          Download this flowchart
        </a>
      )}
      <div className="sm:sr-only print:hidden">
        <figcaption className="font-semibold">{flow.title}</figcaption>
        <ol className="mt-4">
          {flow.steps.map((step, index) => (
            <li key={step.label}>
              <div className={`rounded-md border px-3 py-2 ${step.branches ? "border-primary bg-primary/5" : "bg-muted/40"}`}>
                {step.who && <p className="text-xs font-semibold uppercase tracking-wide text-primary">{step.who}</p>}
                <p className="text-sm font-medium">{step.label}</p>
                {step.note && <p className="text-sm text-muted-foreground">{step.note}</p>}
              </div>
              {step.branches && (
                <ul className="mt-2 space-y-2">
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
                <div aria-hidden className="flex flex-col items-center py-1 text-muted-foreground">
                  <span className="h-2 w-px bg-border" />
                  {step.next && <span className="text-xs font-semibold">{step.next}</span>}
                  <ArrowDown className="size-4" />
                </div>
              )}
            </li>
          ))}
        </ol>
      </div>
    </figure>
  );
}
