import { cn } from "@/lib/utils";

const PASSED = [
  ["Lot", "LOT-0412"],
  ["Inspection", "Passed"],
  ["Batch", "BT-0097"],
  ["Dispatch", "Customer named"],
];
const FAILED = [
  ["Lot", "LOT-0413"],
  ["Inspection", "Failed"],
  ["Held", "Not released"],
  ["NCR", "CAPA raised"],
];

function Path({ label, steps, stopped }: { label: string; steps: string[][]; stopped?: boolean }) {
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-muted-foreground">{label}</p>
      <ol className="grid grid-cols-2 gap-px overflow-hidden rounded-md border bg-border sm:grid-cols-4">
        {steps.map(([kind, value], index) => (
          <li
            key={kind}
            className={cn("bg-background p-3", stopped && index >= 1 && "bg-destructive/10")}
          >
            <p className="text-xs text-muted-foreground">{kind}</p>
            <p className={cn("mt-0.5 text-sm font-medium", stopped && index === 2 && "text-destructive")}>{value}</p>
          </li>
        ))}
      </ol>
    </div>
  );
}

/** The product's central promise, drawn: one lot goes on to a customer, and the other cannot. */
export function LotPaths() {
  return (
    <figure className="space-y-4 rounded-lg border bg-muted/40 p-4 sm:p-5">
      <Path label="A lot that passes" steps={PASSED} />
      <Path label="A lot that fails" steps={FAILED} stopped />
      <figcaption className="text-xs text-muted-foreground">Example records, for illustration.</figcaption>
    </figure>
  );
}
