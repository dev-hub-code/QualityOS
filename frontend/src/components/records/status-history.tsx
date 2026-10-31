import { Section } from "@/components/records/parts";
import { humanize } from "@/lib/capa";
import type { StatusChange } from "@/lib/types";

export function StatusHistory({ changes }: { changes: StatusChange[] }) {
  return (
    <Section title="History">
      <ul className="divide-y text-sm">
        {changes.map((change) => (
          <li key={change.at + change.toStatus} className="flex justify-between gap-3 py-2">
            <span>
              {humanize(change.toStatus)} by {change.actorName}
              {change.note && ` – ${change.note}`}
            </span>
            <span className="shrink-0 text-muted-foreground">{new Date(change.at).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </Section>
  );
}
