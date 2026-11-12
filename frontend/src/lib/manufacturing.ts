import type { ManufacturingModel, PlanStatus, RevisionStatus, WorkOrderStatus } from "@/lib/types";

export type DefinitionKind = "BOM" | "RECIPE" | "ROUTING";

/** Where each kind lives, in the API and in the app, and the permissions that change it. */
export const DEFINITIONS: Record<DefinitionKind, { path: string; title: string; one: string; manage: string; approve: string }> = {
  BOM: { path: "/manufacturing/boms", title: "BOMs", one: "BOM", manage: "BOM_MANAGE", approve: "BOM_APPROVE" },
  RECIPE: { path: "/manufacturing/recipes", title: "Recipes", one: "recipe", manage: "RECIPE_MANAGE", approve: "RECIPE_APPROVE" },
  ROUTING: { path: "/manufacturing/routings", title: "Routings", one: "routing", manage: "ROUTING_MANAGE", approve: "ROUTING_APPROVE" },
};

export const MODEL_LABELS: Record<ManufacturingModel, string> = {
  DISCRETE: "Discrete – assembled from a BOM",
  PROCESS: "Process – made to a recipe",
  HYBRID: "Hybrid – each product chooses",
};

/** BOMs are for discrete products and recipes for process ones; a hybrid organisation has both. */
export function usesKind(model: ManufacturingModel | undefined, kind: "BOM" | "RECIPE"): boolean {
  if (!model) return false;
  return model === "HYBRID" || (kind === "BOM" ? model === "DISCRETE" : model === "PROCESS");
}

export type RevisionAction = "edit" | "submit" | "discard" | "sendBack" | "approve" | "retire";

/**
 * What a person may do with a revision at its stage. The server decides finally, for example whether the person
 * who submitted may also approve.
 */
export function revisionActions(
  status: RevisionStatus,
  submittedOnce: boolean,
  can: { manage: boolean; approve: boolean },
): RevisionAction[] {
  const actions: RevisionAction[] = [];
  if (status === "DRAFT" && can.manage) actions.push("edit", "submit");
  if (status === "DRAFT" && can.manage && !submittedOnce) actions.push("discard");
  if (status === "SUBMITTED" && can.approve) actions.push("sendBack", "approve");
  if (status === "APPROVED" && can.approve) actions.push("retire");
  return actions;
}

/** A line names a material or a product from one list, so each option carries its kind. */
export function toLineRequest(values: Record<string, unknown>) {
  const [kind, id] = String(values.component ?? "").split(":");
  const scrap = String(values.scrapPercent ?? "").trim();
  return {
    materialId: kind === "material" ? id : null,
    componentProductId: kind === "product" ? id : null,
    quantity: values.quantity,
    uomId: values.uomId,
    scrapPercent: scrap === "" ? null : scrap,
    scales: values.fixed !== true,
    notes: values.notes || null,
  };
}

/** "Target 180, 175 to 185 °C" */
export function describeLimits(parameter: { target: string | null; minimum: string | null; maximum: string | null; unit: string | null }) {
  const trim = (value: string) => (value.includes(".") ? value.replace(/\.?0+$/, "") : value);
  const parts: string[] = [];
  if (parameter.target !== null) parts.push(`target ${trim(parameter.target)}`);
  if (parameter.minimum !== null && parameter.maximum !== null) {
    parts.push(`${trim(parameter.minimum)} to ${trim(parameter.maximum)}`);
  } else if (parameter.minimum !== null) {
    parts.push(`at least ${trim(parameter.minimum)}`);
  } else if (parameter.maximum !== null) {
    parts.push(`at most ${trim(parameter.maximum)}`);
  }
  const text = parts.join(", ");
  const sentence = text.charAt(0).toUpperCase() + text.slice(1);
  return parameter.unit ? `${sentence} ${parameter.unit}` : sentence;
}

export type WorkOrderAction = "edit" | "submit" | "sendBack" | "approve" | "release" | "start" | "hold" | "resume" | "cancel";

/**
 * What a person may do with a work order at its stage, as the server allows it. Before approval, whoever manages work
 * orders may cancel; once approved, only an approver may, and never after production has started.
 */
export function workOrderActions(
  order: { status: WorkOrderStatus; heldFrom: WorkOrderStatus | null },
  can: { manage: boolean; approve: boolean; release: boolean },
): WorkOrderAction[] {
  const actions: WorkOrderAction[] = [];
  switch (order.status) {
    case "DRAFT":
      if (can.manage) actions.push("edit", "submit", "cancel");
      break;
    case "SUBMITTED":
      if (can.approve) actions.push("approve", "sendBack");
      if (can.manage) actions.push("cancel");
      break;
    case "APPROVED":
      if (can.release) actions.push("release", "hold");
      if (can.approve) actions.push("cancel");
      break;
    case "RELEASED":
      if (can.release) actions.push("start", "hold");
      if (can.approve) actions.push("cancel");
      break;
    case "IN_PROGRESS":
      if (can.release) actions.push("hold");
      break;
    case "ON_HOLD":
      if (can.release) actions.push("resume");
      if (can.approve && order.heldFrom !== "IN_PROGRESS") actions.push("cancel");
      break;
  }
  return actions;
}

export type PlanAction = "edit" | "approve" | "close" | "cancel" | "order";

export function planActions(status: PlanStatus, can: { manage: boolean; approve: boolean; order: boolean }): PlanAction[] {
  const actions: PlanAction[] = [];
  if (status === "DRAFT" && can.manage) actions.push("edit");
  if (status === "DRAFT" && can.approve) actions.push("approve");
  if (status === "APPROVED" && can.order) actions.push("order");
  if (status === "APPROVED" && can.approve) actions.push("close");
  if ((status === "DRAFT" || status === "APPROVED") && can.approve) actions.push("cancel");
  return actions;
}

export const PRIORITY_LABELS = { LOW: "Low", NORMAL: "Normal", HIGH: "High", URGENT: "Urgent" } as const;
