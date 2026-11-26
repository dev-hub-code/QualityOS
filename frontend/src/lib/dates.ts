/**
 * Every date and time a page shows, in one style ("25 Nov 2026, 10:47 pm"), whatever the browser's locale. Pages
 * that called toLocaleString() bare showed "11/25/2026, 10:47:15 PM" on US-English browsers.
 */
export function formatDateTime(value: string | number | Date): string {
  return new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

export function formatDay(value: string | number | Date): string {
  return new Date(value).toLocaleDateString("en-IN", { dateStyle: "medium" });
}

/**
 * A calendar day with no time of day, given as "yyyy-mm-dd" or as that day's midnight UTC. Read in the browser's zone,
 * midnight UTC shows as 5:30 am in India, or the day before west of Greenwich.
 */
export function formatCalendarDay(value: string | null | undefined): string {
  if (!value) return "";
  const day = value.length === 10 ? `${value}T00:00:00Z` : value;
  return new Date(day).toLocaleDateString("en-IN", { dateStyle: "medium", timeZone: "UTC" });
}

/**
 * Today as "yyyy-mm-dd" on the user's own calendar. `toISOString()` gives the UTC day, which in India is still
 * yesterday until 05:30, so date fields defaulted with it showed the wrong day.
 */
export function today(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
