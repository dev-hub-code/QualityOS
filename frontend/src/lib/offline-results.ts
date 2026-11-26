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

/** Whose kept results these are. Every read and write is limited to one owner. */
export interface Owner {
  userId: string;
  tenantId: string;
}

/** Each person's results for an inspection are kept apart, so two people on one device never mix. */
type Queue = Record<string, QueuedInspection>;

const slot = (owner: Owner, inspectionId: string) =>
  `${owner.tenantId}/${owner.userId}/${inspectionId}`;

const owns = (owner: Owner, item: QueuedInspection) =>
  item.userId === owner.userId && item.tenantId === owner.tenantId;

function read(): Queue {
  try {
    const raw = window.localStorage.getItem(KEY);
    const stored = raw ? (JSON.parse(raw) as Queue) : {};
    // Results kept before queues were per person are filed under the person who kept them.
    return Object.fromEntries(
      Object.values(stored).map((item) => [
        slot(item, item.inspectionId),
        item,
      ]),
    );
  } catch {
    return {};
  }
}

/** @return false when the device would not keep them, such as with storage full or blocked */
function write(queue: Queue): boolean {
  let kept = true;
  try {
    if (Object.keys(queue).length === 0) window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, JSON.stringify(queue));
  } catch {
    kept = false;
  }
  window.dispatchEvent(new Event(CHANGED_EVENT));
  return kept;
}

/** Only inspection results are kept on the device. Anything else saved without a connection is simply not saved. */
export const NOT_SAVED_OFFLINE = "No connection, so this was not saved. Try again when you are back online.";

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

/**
 * Keeps results for one person. Only what that same person kept for the inspection is merged with them.
 *
 * @return false when the device could not keep them, so the screen must say they are not saved
 */
export function queueResults(
  owner: Omit<QueuedInspection, "entries" | "savedAt" | "error">,
  entries: QueuedEntry[],
): boolean {
  const queue = read();
  const key = slot(owner, owner.inspectionId);
  const kept = queue[key];
  queue[key] = {
    ...owner,
    entries: mergeEntries(
      kept && owns(owner, kept) ? kept.entries : [],
      entries,
    ),
    savedAt: new Date().toISOString(),
  };
  return write(queue);
}

export function queued(): QueuedInspection[] {
  return Object.values(read());
}

/** What this person kept on this device, in this organisation. Another person's stays hidden. */
export function queuedBy(owner: Owner): QueuedInspection[] {
  return queued().filter((item) => owns(owner, item));
}

export function queuedFor(
  owner: Owner,
  inspectionId: string,
): QueuedInspection | undefined {
  const item = read()[slot(owner, inspectionId)];
  return item && owns(owner, item) ? item : undefined;
}

export function discard(owner: Owner, inspectionId: string) {
  const queue = read();
  delete queue[slot(owner, inspectionId)];
  write(queue);
}

/**
 * Removes what was sent, and only that. A measurement kept while the request was out, from this screen or another
 * tab, stays to be sent next time.
 */
function removeSent(owner: Owner, inspectionId: string, sent: QueuedEntry[]) {
  const queue = read();
  const key = slot(owner, inspectionId);
  const item = queue[key];
  if (!item) return;
  const sentAt = new Map(
    sent.map((entry) => [
      sampleKey(entry.parameterCode, entry.sampleNumber),
      entry.measuredAt,
    ]),
  );
  const left = item.entries.filter((entry) => {
    const at = sentAt.get(sampleKey(entry.parameterCode, entry.sampleNumber));
    return at === undefined || entry.measuredAt > at;
  });
  if (left.length === 0) delete queue[key];
  else queue[key] = { ...item, entries: left, error: undefined };
  write(queue);
}

function markRefused(owner: Owner, inspectionId: string, error: string) {
  const queue = read();
  const key = slot(owner, inspectionId);
  if (queue[key]) {
    queue[key] = { ...queue[key], error };
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
  me: Owner,
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
      removeSent(me, item.inspectionId, item.entries);
      sent.push(item.inspectionId);
    } catch (error) {
      if (isConnectionLost(error)) break;
      markRefused(
        me,
        item.inspectionId,
        error instanceof Error ? error.message : "The results were refused.",
      );
    }
  }
  return sent;
}
