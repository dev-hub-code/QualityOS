export const PERIODS = [
  ["TODAY", "Today"],
  ["LAST_7", "Last 7 days"],
  ["LAST_30", "Last 30 days"],
  ["LAST_90", "Last 90 days"],
  ["THIS_MONTH", "Current month"],
  ["LAST_MONTH", "Previous month"],
] as const;

export type PeriodName = (typeof PERIODS)[number][0];

const day = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;

export function presetPeriod(name: PeriodName, today = new Date()): { from: string; to: string } {
  const back = (days: number) => new Date(today.getFullYear(), today.getMonth(), today.getDate() - days);
  const month = (offset: number) => new Date(today.getFullYear(), today.getMonth() + offset, 1);
  switch (name) {
    case "TODAY":
      return { from: day(today), to: day(today) };
    case "LAST_7":
      return { from: day(back(6)), to: day(today) };
    case "LAST_30":
      return { from: day(back(29)), to: day(today) };
    case "LAST_90":
      return { from: day(back(89)), to: day(today) };
    case "THIS_MONTH":
      return { from: day(month(0)), to: day(today) };
    case "LAST_MONTH":
      return { from: day(month(-1)), to: day(new Date(today.getFullYear(), today.getMonth(), 0)) };
  }
}
