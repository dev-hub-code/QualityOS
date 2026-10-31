const MEGABYTE = 1_048_576;
const GIGABYTE_IN_MB = 1024;

/** Whole units with no limit print as used alone, because a percentage of nothing would mislead. */
export function usageText(used: number, limit: number | null) {
  if (limit === null) return `${used.toLocaleString("en-IN")} (no limit)`;
  return `${used.toLocaleString("en-IN")} / ${limit.toLocaleString("en-IN")} (${Math.round((100 * used) / limit)}%)`;
}

const size = (megabytes: number) =>
  megabytes >= GIGABYTE_IN_MB ? `${(megabytes / GIGABYTE_IN_MB).toFixed(1)} GB` : `${megabytes.toFixed(1)} MB`;

export function storageText(bytes: number, limitMb: number | null) {
  const used = bytes / MEGABYTE;
  if (limitMb === null) return `${size(used)} (no limit)`;
  return `${size(used)} / ${size(limitMb)} (${Math.round((100 * used) / limitMb)}%)`;
}
