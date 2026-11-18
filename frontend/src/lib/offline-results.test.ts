import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import {
  isConnectionLost,
  mergeEntries,
  queueResults,
  queued,
  queuedBy,
  queuedFor,
  sendQueued,
  type QueuedEntry,
} from "@/lib/offline-results";

const me = { userId: "u-1", tenantId: "t-1" };
const owner = { inspectionId: "i-1", inspectionNumber: "INS-1", ...me };

function entry(sample: number, value: string, measuredAt: string): QueuedEntry {
  return {
    parameterCode: "DIA",
    sampleNumber: sample,
    valueNumeric: value,
    measuredAt,
  };
}

afterEach(() => window.localStorage.clear());

describe("offline results", () => {
  it("keeps the later measurement of the same sample", () => {
    const merged = mergeEntries(
      [
        entry(1, "20.1", "2026-11-18T10:00:00Z"),
        entry(2, "20.0", "2026-11-18T10:00:00Z"),
      ],
      [
        entry(1, "19.9", "2026-11-18T09:00:00Z"),
        entry(2, "20.05", "2026-11-18T11:00:00Z"),
      ],
    );
    expect(merged.map((item) => item.valueNumeric)).toEqual(["20.1", "20.05"]);
  });

  it("tells a lost connection from a refusal", () => {
    expect(isConnectionLost(new TypeError("Failed to fetch"))).toBe(true);
    expect(isConnectionLost(new ApiError(409, "CONFLICT", "Closed"))).toBe(
      false,
    );
  });

  it("sends only the signed-in person's results, and keeps refused ones with the reason", async () => {
    queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
    queueResults({ ...owner, inspectionId: "i-2", userId: "u-2" }, [
      entry(1, "20", "2026-11-18T10:00:00Z"),
    ]);
    queueResults({ ...owner, inspectionId: "i-3" }, [
      entry(1, "20", "2026-11-18T10:00:00Z"),
    ]);
    const post = vi.fn(async (path: string) => {
      if (path.includes("i-3"))
        throw new ApiError(422, "RULE", "This inspection is closed.");
    });

    expect(await sendQueued(me, post)).toEqual(["i-1"]);
    expect(post).toHaveBeenCalledTimes(2);
    expect(queuedBy(me)).toEqual([
      expect.objectContaining({
        inspectionId: "i-3",
        error: "This inspection is closed.",
      }),
    ]);
    expect(queued().map((item) => item.inspectionId)).toContain("i-2");

    // Refused results are not sent again on their own.
    expect(await sendQueued(me, post)).toEqual([]);
    expect(post).toHaveBeenCalledTimes(2);
  });

  it("keeps everything when the connection drops again", async () => {
    queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
    const post = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });
    expect(await sendQueued(me, post)).toEqual([]);
    expect(queuedBy(me)).toEqual([
      expect.not.objectContaining({ error: expect.anything() }),
    ]);
  });

  it("never mixes two people's values for the same inspection on a shared device", async () => {
    const a = { userId: "inspector-a", tenantId: "t-1" };
    const b = { userId: "inspector-b", tenantId: "t-1" };
    queueResults({ ...owner, ...a }, [
      entry(1, "20.01", "2026-11-18T09:00:00Z"),
    ]);
    queueResults({ ...owner, ...b }, [
      entry(2, "20.02", "2026-11-18T09:05:00Z"),
    ]);

    expect(
      queuedFor(a, "i-1")?.entries.map((item) => item.valueNumeric),
    ).toEqual(["20.01"]);
    expect(
      queuedFor(b, "i-1")?.entries.map((item) => item.valueNumeric),
    ).toEqual(["20.02"]);

    const post = vi.fn<(path: string, body: unknown) => Promise<undefined>>(
      async () => undefined,
    );
    expect(await sendQueued(b, post)).toEqual(["i-1"]);
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][1]).toEqual({
      results: [entry(2, "20.02", "2026-11-18T09:05:00Z")],
    });
    // A's values stay on the device, untouched, for A to send.
    expect(
      queuedFor(a, "i-1")?.entries.map((item) => item.valueNumeric),
    ).toEqual(["20.01"]);
    expect(queuedFor(b, "i-1")).toBeUndefined();
  });

  it("keeps the same person's values apart in another organisation", () => {
    queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
    queueResults({ ...owner, tenantId: "t-2" }, [
      entry(1, "21", "2026-11-18T11:00:00Z"),
    ]);
    expect(queuedBy(me).map((item) => item.entries[0].valueNumeric)).toEqual([
      "20",
    ]);
    expect(
      queuedBy({ ...me, tenantId: "t-2" }).map(
        (item) => item.entries[0].valueNumeric,
      ),
    ).toEqual(["21"]);
  });

  it("files values kept before queues were per person under the person who kept them", () => {
    window.localStorage.setItem(
      "qualityos.offline-results",
      JSON.stringify({
        "i-1": {
          ...owner,
          entries: [entry(1, "20", "2026-11-18T10:00:00Z")],
          savedAt: "x",
        },
      }),
    );
    expect(queuedFor(me, "i-1")?.entries).toHaveLength(1);
    expect(queuedFor({ ...me, userId: "u-2" }, "i-1")).toBeUndefined();
  });

  it("says when the device could not keep the values", () => {
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new DOMException("full", "QuotaExceededError");
      });
    expect(queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")])).toBe(
      false,
    );
    setItem.mockRestore();
    expect(queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")])).toBe(
      true,
    );
  });
});
