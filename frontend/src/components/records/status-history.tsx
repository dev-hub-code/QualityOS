import { Section } from "@/components/records/parts";
import { humanize } from "@/lib/capa";
import type { StatusChange } from "@/lib/types";

/** @param label names a status; by default as it is written */
export function StatusHistory({ changes, label = humanize }: { changes: StatusChange[]; label?: (status: string) => string }) {
  return (
    <Section title="History">
      <ul className="divide-y text-sm">
        {changes.map((change) => (
          <li key={change.at + change.toStatus} className="flex justify-between gap-3 py-2">
            <span>
              {label(change.toStatus)} by {change.actorName}
              {change.note && ` – ${change.note}`}
            </span>
            <span className="shrink-0 text-muted-foreground">{new Date(change.at).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </Section>
  );
}
