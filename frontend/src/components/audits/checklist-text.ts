export interface ChecklistLine {
  question: string;
  clauseReference: string | null;
}

/** One question a line, with an optional clause after a bar: "Are gauges labelled? | 7.1.5". */
export function parseChecklist(text: string): ChecklistLine[] {
  return text
    .split("\n")
    .map((line) => line.split("|").map((part) => part.trim()))
    .filter(([question]) => question)
    .map(([question, clause]) => ({ question, clauseReference: clause || null }));
}

export function formatChecklist(lines: ChecklistLine[]): string {
  return lines
    .map(({ question, clauseReference }) => (clauseReference ? `${question} | ${clauseReference}` : question))
    .join("\n");
}
