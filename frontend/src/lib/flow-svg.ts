import type { BranchTone, Flow, FlowStep } from "@/lib/doc-flows";

/**
 * Draws a flow as a standalone SVG flowchart: the main path down the left, start and end as rounded terminals,
 * decisions as hexagons, and each branch as an outcome box to the right. Standalone, so it works as an image, prints,
 * and downloads as it is shown; its colours are fixed for the same reason.
 */

const WIDTH = 760;
const MAIN_X = 24;
const MAIN_W = 330;
const MAIN_CX = MAIN_X + MAIN_W / 2;
const BRANCH_X = 430;
const BRANCH_W = 306;
const ROW_GAP = 36;
const BRANCH_GAP = 10;
const FONT = "Inter, 'Segoe UI', Helvetica, Arial, sans-serif";

const COLOURS = {
  ink: "#1b2340",
  muted: "#4b5563",
  arrow: "#5b6478",
  brand: "#2f4590",
  brandDark: "#1c2a62",
  step: { fill: "#eef2fb", stroke: "#2f4590" },
  decision: { fill: "#fff7e6", stroke: "#c98a12" },
  tone: {
    stop: { fill: "#fdf0f0", stroke: "#c0504d", text: "#a23b38" },
    good: { fill: "#eef8f1", stroke: "#3f9a63", text: "#2e7d4f" },
    neutral: { fill: "#f3f4f6", stroke: "#8a8f98", text: "#374151" },
  } satisfies Record<BranchTone, { fill: string; stroke: string; text: string }>,
};

/** Splits text into lines of about {@code width} characters, never breaking a word. */
export function wrap(text: string, width: number): string[] {
  const lines: string[] = [];
  let line = "";
  for (const word of text.split(/\s+/).filter(Boolean)) {
    if (line && line.length + 1 + word.length > width) {
      lines.push(line);
      line = word;
    } else {
      line = line ? `${line} ${word}` : word;
    }
  }
  if (line) lines.push(line);
  return lines;
}

function escape(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function text(x: number, y: number, lines: string[], size: number, weight: number, fill: string, anchor = "middle") {
  const height = size + 4;
  return lines
    .map(
      (line, index) =>
        `<text x="${x}" y="${y + index * height}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}">${escape(line)}</text>`,
    )
    .join("");
}

interface Laid {
  height: number;
  draw: (top: number) => string;
}

/** A step of the main path: a terminal at either end, a hexagon when it decides, else a box. */
function step(item: FlowStep, terminal: boolean): Laid {
  const decision = Boolean(item.branches?.length);
  const inset = decision ? 30 : 16;
  const labelLines = wrap(item.label, Math.floor((MAIN_W - 2 * inset) / 7.6));
  const noteLines = item.note ? wrap(item.note, Math.floor((MAIN_W - 2 * inset) / 6.4)) : [];
  const height = 14 + (item.who ? 16 : 0) + labelLines.length * 18 + (noteLines.length ? 4 + noteLines.length * 16 : 0) + 10;
  const dark = terminal && !decision;
  return {
    height,
    draw: (top) => {
      let shape: string;
      if (decision) {
        const mid = top + height / 2;
        const points = [
          [MAIN_X + 16, top],
          [MAIN_X + MAIN_W - 16, top],
          [MAIN_X + MAIN_W, mid],
          [MAIN_X + MAIN_W - 16, top + height],
          [MAIN_X + 16, top + height],
          [MAIN_X, mid],
        ];
        shape = `<polygon points="${points.map((point) => point.join(",")).join(" ")}" fill="${COLOURS.decision.fill}" stroke="${COLOURS.decision.stroke}" stroke-width="1.5"/>`;
      } else {
        const radius = terminal ? Math.min(height / 2, 24) : 8;
        const fill = dark ? COLOURS.brandDark : COLOURS.step.fill;
        const stroke = dark ? COLOURS.brandDark : COLOURS.step.stroke;
        shape = `<rect x="${MAIN_X}" y="${top}" width="${MAIN_W}" height="${height}" rx="${radius}" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`;
      }
      let y = top + 14;
      let body = "";
      if (item.who) {
        body += text(MAIN_CX, y + 9, [item.who.toUpperCase()], 10.5, 700, dark ? "#c7d2fe" : COLOURS.brand);
        y += 16;
      }
      body += text(MAIN_CX, y + 13, labelLines, 14, 650, dark ? "#ffffff" : COLOURS.ink);
      y += labelLines.length * 18;
      if (noteLines.length) body += text(MAIN_CX, y + 16, noteLines, 12.5, 400, dark ? "#dbe2ff" : COLOURS.muted);
      return shape + body;
    },
  };
}

/** An outcome to the right of a decision: when it happens, in its tone's colour, and what follows. */
function branch(item: { when: string; then: string; tone?: BranchTone }): Laid {
  const tone = COLOURS.tone[item.tone ?? "neutral"];
  const whenLines = wrap(item.when, Math.floor((BRANCH_W - 24) / 6.9));
  const thenLines = wrap(item.then, Math.floor((BRANCH_W - 24) / 6.4));
  const height = 10 + whenLines.length * 16 + 4 + thenLines.length * 16 + 8;
  return {
    height,
    draw: (top) =>
      `<rect x="${BRANCH_X}" y="${top}" width="${BRANCH_W}" height="${height}" rx="8" fill="${tone.fill}" stroke="${tone.stroke}" stroke-width="1.2"/>` +
      text(BRANCH_X + 12, top + 22, whenLines, 12.5, 700, tone.text, "start") +
      text(BRANCH_X + 12, top + 26 + whenLines.length * 16, thenLines, 12.5, 400, COLOURS.ink, "start"),
  };
}

function arrow(path: string) {
  return `<path d="${path}" fill="none" stroke="${COLOURS.arrow}" stroke-width="1.5" marker-end="url(#arrow)"/>`;
}

function legend(top: number) {
  const items: [string, string][] = [
    [`<rect x="0" y="0" width="26" height="14" rx="7" fill="${COLOURS.brandDark}"/>`, "Start or end"],
    [`<rect x="0" y="0" width="26" height="14" rx="3" fill="${COLOURS.step.fill}" stroke="${COLOURS.step.stroke}"/>`, "Step"],
    [`<polygon points="5,0 21,0 26,7 21,14 5,14 0,7" fill="${COLOURS.decision.fill}" stroke="${COLOURS.decision.stroke}"/>`, "Decision"],
    [`<rect x="0" y="0" width="26" height="14" rx="3" fill="${COLOURS.tone.good.fill}" stroke="${COLOURS.tone.good.stroke}"/>`, "Goes ahead"],
    [`<rect x="0" y="0" width="26" height="14" rx="3" fill="${COLOURS.tone.stop.fill}" stroke="${COLOURS.tone.stop.stroke}"/>`, "Stops or fails"],
    [`<rect x="0" y="0" width="26" height="14" rx="3" fill="${COLOURS.tone.neutral.fill}" stroke="${COLOURS.tone.neutral.stroke}"/>`, "Other outcome"],
  ];
  return items
    .map(([swatch, label], index) => {
      const x = MAIN_X + index * 122;
      return `<g transform="translate(${x},${top})">${swatch}<text x="32" y="11.5" font-size="11.5" fill="${COLOURS.muted}">${escape(label)}</text></g>`;
    })
    .join("");
}

export function flowSvg(flow: Flow): string {
  const titleLines = wrap(flow.title, 80);
  let y = 22 + titleLines.length * 24;
  let body = text(MAIN_X, 30, titleLines, 18, 700, COLOURS.ink, "start");
  flow.steps.forEach((item, index) => {
    const terminal = index === 0 || index === flow.steps.length - 1;
    const node = step(item, terminal);
    const outcomes = (item.branches ?? []).map(branch);
    const outcomesHeight = outcomes.reduce((sum, outcome) => sum + outcome.height, 0) + BRANCH_GAP * Math.max(0, outcomes.length - 1);
    const rowHeight = Math.max(node.height, outcomesHeight);
    // A labelled arrow needs room for its label.
    const nodeTop = y;
    body += node.draw(nodeTop);
    let branchTop = y + Math.max(0, (node.height - outcomesHeight) / 2);
    const fromX = MAIN_X + MAIN_W;
    const fromY = nodeTop + node.height / 2;
    const bend = (fromX + BRANCH_X) / 2;
    for (const outcome of outcomes) {
      const toY = branchTop + outcome.height / 2;
      body += arrow(`M${fromX} ${fromY} H${bend} V${toY} H${BRANCH_X - 2}`);
      body += outcome.draw(branchTop);
      branchTop += outcome.height + BRANCH_GAP;
    }
    y += rowHeight;
    if (index < flow.steps.length - 1) {
      body += arrow(`M${MAIN_CX} ${nodeTop + node.height} V${y + ROW_GAP - 2}`);
      if (item.next) {
        body += text(MAIN_CX + 8, nodeTop + node.height + 16, [item.next], 12, 700, COLOURS.decision.stroke, "start");
      }
      y += ROW_GAP;
    }
  });
  y += 26;
  body += legend(y);
  const height = y + 26;
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${WIDTH} ${height}" width="${WIDTH}" height="${height}" font-family="${FONT}" role="img">` +
    `<title>${escape(flow.title)}</title>` +
    `<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="${COLOURS.arrow}"/></marker></defs>` +
    `<rect width="${WIDTH}" height="${height}" fill="#ffffff"/>` +
    body +
    `</svg>`
  );
}

/** The flow as a data URL, to show as an image and to download. */
export function flowImage(flow: Flow): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(flowSvg(flow))}`;
}
