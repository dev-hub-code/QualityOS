import { describe, expect, it } from "vitest";
import { DOC_FLOWS, OVERVIEW_FLOW } from "./doc-flows";
import { flowImage, flowSvg, wrap } from "./flow-svg";

describe("flowchart images", () => {
  it("wraps text without breaking words", () => {
    expect(wrap("Raise a purchase order and receive against it", 20)).toEqual([
      "Raise a purchase",
      "order and receive",
      "against it",
    ]);
    expect(wrap("Supercalifragilistic", 5)).toEqual(["Supercalifragilistic"]);
  });

  it("escapes text, so a label cannot break the image", () => {
    const svg = flowSvg({ title: "A & B <c>", steps: [{ label: 'say "hi"' }, { label: "it's done" }] });
    expect(svg).toContain("A &amp; B &lt;c&gt;");
    expect(svg).toContain("say &quot;hi&quot;");
    expect(svg).toContain("it&#39;s done");
    expect(svg).not.toContain("<c>");
  });

  it("draws every step and branch of every workflow", () => {
    for (const flow of [OVERVIEW_FLOW, ...Object.values(DOC_FLOWS).flat()]) {
      const svg = flowSvg(flow);
      expect(svg.startsWith("<svg")).toBe(true);
      const branches = flow.steps.flatMap((step) => step.branches ?? []);
      expect(svg.match(/marker-end/g)?.length, flow.title).toBe(flow.steps.length - 1 + branches.length);
      expect(svg.match(/<polygon points="\d/g)?.length ?? 0, flow.title).toBe(
        // One more for the legend's swatch.
        flow.steps.filter((step) => step.branches?.length).length + 1,
      );
      expect(new DOMParser().parseFromString(svg, "image/svg+xml").querySelector("parsererror"), flow.title).toBeNull();
      expect(flowImage(flow).startsWith("data:image/svg+xml")).toBe(true);
    }
  });
});
