export const FISHBONE_BRANCHES = ["Man", "Machine", "Material", "Method", "Measurement", "Environment"];
export const WHY_COUNT = 5;

/** Blank whys are dropped, keeping their order, so that a short chain of causes is valid. */
export function toFiveWhys(whys: string[]): string[] {
  return whys.map((why) => why.trim()).filter(Boolean);
}

/** Each branch is typed as one cause per line. */
export function toFishbone(branches: Record<string, string>): Record<string, string[]> {
  const entries = Object.entries(branches)
    .map(([branch, text]): [string, string[]] => [
      branch,
      text.split("\n").map((line) => line.trim()).filter(Boolean),
    ])
    .filter(([, causes]) => causes.length > 0);
  return Object.fromEntries(entries);
}

export const humanize = (status: string) => status.replaceAll("_", " ").toLowerCase();

export function isReadyForVerification(actions: { completedAt: string | null }[]): boolean {
  return actions.length > 0 && actions.every((action) => action.completedAt !== null);
}
