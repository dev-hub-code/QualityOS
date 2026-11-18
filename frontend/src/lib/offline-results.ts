import { ApiError } from "@/lib/api";
import { sampleKey, type ResultEntry } from "@/lib/inspection";

/** A result typed while the connection was down, with when it was measured. */
export interface QueuedEntry extends ResultEntry {
  measuredAt: string;
}

/** The results one person recorded offline for one inspection. */
export interface QueuedInspection {
  inspectionId: string;
  inspectionNumber: string;
  /** Only the person who recorded them, in the same organisation, sends them. */
  userId: string;
  tenantId: string;
  entries: QueuedEntry[];
  savedAt: string;
  /** Why the server refused them last time, such as the inspection being closed meanwhile. */
  error?: string;
}

const KEY = "qualityos.offline-results";
export const CHANGED_EVENT = "qualityos:offline-results";

type Queue = Record<string, QueuedInspection>;

function read(): Queue {
  try {
    const raw = window.localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Queue) : {};
  } catch {
    return {};
  }
}

function write(queue: Queue) {
  try {
    if (Object.keys(queue).length === 0) window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, JSON.stringify(queue));
  } catch {
    // Storage full or blocked: what was queued before stays as it was.
  }
  window.dispatchEvent(new Event(CHANGED_EVENT));
}

/** True when a request failed for want of a connection, not because the server refused it. */
export function isConnectionLost(error: unknown): boolean {
  if (error instanceof ApiError) return false;
  return (
    error instanceof TypeError ||
    (typeof navigator !== "undefined" && navigator.onLine === false)
  );
}

/** Adds results to those kept for an inspection. For the same sample, the later measurement wins. */
export function mergeEntries(
  kept: QueuedEntry[],
  added: QueuedEntry[],
): QueuedEntry[] {
  const byKey = new Map(
    kept.map((entry) => [
      sampleKey(entry.parameterCode, entry.sampleNumber),
      entry,
    ]),
  );
  for (const entry of added) {
    const key = sampleKey(entry.parameterCode, entry.sampleNumber);
    const current = byKey.get(key);
    if (!current || current.measuredAt <= entry.measuredAt)
      byKey.set(key, entry);
  }
  return [...byKey.values()];
}

export function queueResults(
  owner: Omit<QueuedInspection, "entries" | "savedAt" | "error">,
  entries: QueuedEntry[],
) {
  const queue = read();
  const kept = queue[owner.inspectionId];
  queue[owner.inspectionId] = {
    ...owner,
    entries: mergeEntries(kept?.entries ?? [], entries),
    savedAt: new Date().toISOString(),
  };
  write(queue);
}

export function queued(): QueuedInspection[] {
  return Object.values(read());
}

/** What this person kept on this device, in this organisation. Another person's stays hidden. */
export function queuedBy(me: {
  userId: string;
  tenantId: string;
}): QueuedInspection[] {
  return queued().filter(
    (item) => item.userId === me.userId && item.tenantId === me.tenantId,
  );
}

export function queuedFor(inspectionId: string): QueuedInspection | undefined {
  return read()[inspectionId];
}

export function discard(inspectionId: string) {
  const queue = read();
  delete queue[inspectionId];
  write(queue);
}

function markRefused(inspectionId: string, error: string) {
  const queue = read();
  if (queue[inspectionId]) {
    queue[inspectionId] = { ...queue[inspectionId], error };
    write(queue);
  }
}

/**
 * Sends what the signed-in person kept offline. Sent results leave the device; refused ones stay with the reason,
 * and are tried again only when the person asks. A lost connection leaves everything for the next try.
 *
 * @return the inspections whose results reached the server
 */
export async function sendQueued(
  me: { userId: string; tenantId: string },
  post: (path: string, body: unknown) => Promise<unknown>,
  retryRefused = false,
): Promise<string[]> {
  const sent: string[] = [];
  for (const item of queuedBy(me)) {
    if (item.error && !retryRefused) continue;
    try {
      await post(`/inspections/${item.inspectionId}/execute`, {
        results: item.entries,
      });
      discard(item.inspectionId);
      sent.push(item.inspectionId);
    } catch (error) {
      if (isConnectionLost(error)) break;
      markRefused(
        item.inspectionId,
        error instanceof Error ? error.message : "The results were refused.",
      );
    }
  }
  return sent;
}
