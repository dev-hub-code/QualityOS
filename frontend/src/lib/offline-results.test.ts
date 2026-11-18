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

  describe("edge cases from the November review", () => {
    it("keeps results measured while an earlier send was on its way", async () => {
      queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
      // Another tab, or the same screen, keeps a new measurement while the request is out.
      const post = vi.fn(async () => {
        queueResults(owner, [
          entry(2, "20.02", "2026-11-18T10:05:00Z"),
          entry(1, "19.99", "2026-11-18T10:06:00Z"),
        ]);
      });

      expect(await sendQueued(me, post)).toEqual(["i-1"]);

      const left = queuedFor(me, "i-1");
      expect(
        left?.entries.map((item) => [item.sampleNumber, item.valueNumeric]),
      ).toEqual([
        [1, "19.99"],
        [2, "20.02"],
      ]);
    });

    it("two tabs sending at once both finish, leaving nothing behind", async () => {
      queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
      queueResults({ ...owner, inspectionId: "i-2" }, [
        entry(1, "20", "2026-11-18T10:00:00Z"),
      ]);
      // The server keeps one result per sample, so a result sent by both tabs is stored once.
      const post = vi.fn(
        () => new Promise<void>((resolve) => setTimeout(resolve, 5)),
      );

      const [first, second] = await Promise.all([
        sendQueued(me, post),
        sendQueued(me, post),
      ]);

      expect(new Set([...first, ...second])).toEqual(new Set(["i-1", "i-2"]));
      expect(queuedBy(me)).toEqual([]);
    });

    it("sends nothing kept in one organisation while signed in to another, and sends it on return", async () => {
      queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
      const post = vi.fn(async () => undefined);

      expect(await sendQueued({ ...me, tenantId: "t-2" }, post)).toEqual([]);
      expect(post).not.toHaveBeenCalled();
      expect(queuedFor(me, "i-1")).toBeDefined();

      expect(await sendQueued(me, post)).toEqual(["i-1"]);
      expect(post).toHaveBeenCalledWith(
        "/inspections/i-1/execute",
        expect.anything(),
      );
    });

    it("sends refused results again only when asked, and clears them once taken", async () => {
      queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]);
      const closed = vi.fn(async () => {
        throw new ApiError(
          422,
          "RULE",
          "Results cannot be changed after submission.",
        );
      });
      await sendQueued(me, closed);
      expect(queuedFor(me, "i-1")?.error).toBe(
        "Results cannot be changed after submission.",
      );

      // Reopened by the manager, then sent again from the banner.
      const taken = vi.fn(async () => undefined);
      expect(await sendQueued(me, taken)).toEqual([]);
      expect(await sendQueued(me, taken, true)).toEqual(["i-1"]);
      expect(queuedFor(me, "i-1")).toBeUndefined();
    });

    it("starts afresh, without failing, when what the device kept is unreadable or was cleared", async () => {
      window.localStorage.setItem("qualityos.offline-results", "{not json");
      expect(queued()).toEqual([]);
      expect(
        queueResults(owner, [entry(1, "20", "2026-11-18T10:00:00Z")]),
      ).toBe(true);
      expect(queuedFor(me, "i-1")).toBeDefined();

      // Cleared by the browser or another tab: nothing to send, and nothing breaks.
      window.localStorage.clear();
      const post = vi.fn(async () => undefined);
      expect(await sendQueued(me, post)).toEqual([]);
      expect(post).not.toHaveBeenCalled();
    });
  });
});
