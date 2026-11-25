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
