import { afterEach, describe, expect, it, vi } from "vitest";
import { lookupAll } from "./parts";

function pages(total: number, perPage = 100) {
  const fetch = vi.fn(async (url: string) => {
    const page = Number(new URL(String(url), "http://localhost").searchParams.get("page"));
    const count = Math.min(perPage, total - page * perPage);
    const items = Array.from({ length: count }, (_, index) => ({ id: `${page}-${index}` }));
    return Response.json({
      success: true,
      data: { items, page, size: perPage, totalItems: total, totalPages: Math.ceil(total / perPage) },
    });
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

describe("lookupAll", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reads every page, so a picker is not cut off at the first hundred", async () => {
    const fetch = pages(250);
    const items = await lookupAll<{ id: string }>("/facilities", "&status=APPROVED");
    expect(items).toHaveLength(250);
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(String(fetch.mock.calls[2][0])).toContain("/facilities?size=100&page=2&status=APPROVED");
  });

  it("asks once when everything fits on one page", async () => {
    const fetch = pages(3);
    expect(await lookupAll("/products")).toHaveLength(3);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("stops at fifty pages", async () => {
    const fetch = pages(9_000);
    expect(await lookupAll("/materials")).toHaveLength(5_000);
    expect(fetch).toHaveBeenCalledTimes(50);
  });
});
