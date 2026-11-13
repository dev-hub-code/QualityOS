import type {
  CheckpointStage,
  GateStatus,
  LossCategory,
  ManufacturingModel,
  OperationStatus,
  PlanStatus,
  ReworkStatus,
  RevisionStatus,
  WorkOrderStatus,
} from "@/lib/types";

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

/** What a lot can still give to ordinary consumption: what remains, less what work orders have set aside. */
export function freeQuantity(lot: { remainingQuantity: string; reservedQuantity?: string | null }): string {
  const free = Number(lot.remainingQuantity) - Number(lot.reservedQuantity ?? 0);
  return String(Number(Math.max(free, 0).toFixed(6)));
}

/** A fresh key for one stock movement, so that sending it twice moves stock once. */
export function newRequestKey(): string {
  return crypto.randomUUID();
}

export type OperationAction =
  | "start"
  | "pause"
  | "resume"
  | "report"
  | "measure"
  | "complete"
  | "assign"
  | "block"
  | "unblock"
  | "skip";

/**
 * What a person may do with an operation, as the server allows it. Operations run only while their order is in
 * progress; one given to someone else is theirs unless the person supervises.
 */
export function operationActions(
  operation: { status: OperationStatus; workOrderStatus: WorkOrderStatus; processed: string; operatorId: string | null },
  me: { id: string | undefined; execute: boolean; supervise: boolean },
): OperationAction[] {
  const actions: OperationAction[] = [];
  const running = operation.workOrderStatus === "IN_PROGRESS";
  const onFloor = running || operation.workOrderStatus === "ON_HOLD";
  const mine = operation.operatorId === null || operation.operatorId === me.id || me.supervise;
  if (me.execute && running && mine) {
    if (operation.status === "READY") actions.push("start");
    if (operation.status === "IN_PROGRESS") actions.push("pause");
    if (operation.status === "PAUSED") actions.push("resume");
    if (operation.status === "IN_PROGRESS" || operation.status === "PAUSED") actions.push("report", "measure", "complete");
  }
  if (me.supervise) {
    const open = operation.status !== "COMPLETED" && operation.status !== "CANCELLED";
    const released = onFloor || operation.workOrderStatus === "RELEASED";
    if (released && open) actions.push("assign");
    if (onFloor && open && operation.status !== "BLOCKED") actions.push("block");
    if (onFloor && operation.status === "BLOCKED") actions.push("unblock");
    if (released && ["PENDING", "READY", "BLOCKED"].includes(operation.status) && Number(operation.processed) === 0) {
      actions.push("skip");
    }
  }
  return actions;
}

export const LOSS_CATEGORY_LABELS: Record<LossCategory, string> = {
  MACHINE_BREAKDOWN: "Machine breakdown",
  MATERIAL_SHORTAGE: "Material shortage",
  POWER_FAILURE: "Power failure",
  QUALITY_ISSUE: "Quality issue",
  OPERATOR_UNAVAILABLE: "Operator unavailable",
  PLANNED_MAINTENANCE: "Planned maintenance",
  CHANGEOVER: "Changeover",
  ORDER_ON_HOLD: "Work order on hold",
  OTHER: "Other",
};

/** Running time as hours and minutes, for the floor. */
export function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  if (hours === 0) return `${minutes} min`;
  return `${hours} h ${minutes % 60} min`;
}

/** Good, rejected and scrap add up to the quantity reported, as the server checks. */
export function reportMismatch(values: { quantity: string; goodQuantity: string; rejectedQuantity: string; scrapQuantity: string }): string | null {
  const parts = [values.goodQuantity, values.rejectedQuantity, values.scrapQuantity].map((value) => Number(value || 0));
  const total = Number(parts.reduce((sum, part) => sum + part, 0).toFixed(6));
  return total === Number(values.quantity) ? null : `Good, rejected and scrap make ${total}, not ${Number(values.quantity)}.`;
}

export const STAGE_LABELS: Record<CheckpointStage, string> = {
  OPERATION_START: "Before the operation starts",
  IN_PROCESS: "While it runs",
  OPERATION_COMPLETION: "Before the operation completes",
  FINAL_RELEASE: "Final release",
};

/**
 * Whether a gate can have an inspection started: it is pending or failed, and production is under way. The server
 * also checks the operation is at the right point for the gate's stage.
 */
export function gateInspectable(gate: { status: GateStatus }, orderStatus: WorkOrderStatus, canInspect: boolean): boolean {
  return canInspect && (gate.status === "PENDING" || gate.status === "FAILED") && (orderStatus === "IN_PROGRESS" || orderStatus === "ON_HOLD");
}

export type ReworkAction = "approve" | "start" | "complete" | "reinspect" | "close" | "cancel";

/** What a person may do with a rework order, as the server allows it. */
export function reworkActions(
  rework: { status: ReworkStatus; reinspectionOpen: boolean; reinspectionDecided: boolean },
  can: { manage: boolean; approve: boolean; execute: boolean; inspect: boolean },
): ReworkAction[] {
  const actions: ReworkAction[] = [];
  switch (rework.status) {
    case "OPEN":
      if (can.approve) actions.push("approve");
      if (can.manage) actions.push("cancel");
      break;
    case "APPROVED":
      if (can.execute) actions.push("start");
      if (can.manage) actions.push("cancel");
      break;
    case "IN_PROGRESS":
      if (can.execute) actions.push("complete");
      break;
    case "AWAITING_INSPECTION":
      if (can.inspect && !rework.reinspectionOpen) actions.push("reinspect");
      if (can.manage && rework.reinspectionDecided) actions.push("close");
      break;
  }
  return actions;
}

/** Where a reinspection stands: under way until cancelled, decided once it has a disposition other than hold. */
export function reinspectionState(reinspection: { status: string; disposition: string | null } | null) {
  const live = reinspection !== null && reinspection.status !== "CANCELLED";
  const decided = live && reinspection.disposition !== null && reinspection.disposition !== "HOLD";
  return { reinspectionOpen: live, reinspectionDecided: decided };
}

/** Rework steps typed one per line, blank lines left out. */
export function parseSteps(text: string, workCenterId: string | null): { description: string; workCenterId: string | null }[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((description) => ({ description, workCenterId }));
}

/**
 * What a person may do to finish a work order, as the server allows it: receive its finished goods once nothing blocks
 * them, then close it once nothing blocks that.
 */
export function completionActions(
  completion: { receiveBlockers: string[]; receipt: unknown; closeBlockers: string[] },
  orderStatus: WorkOrderStatus,
  canComplete: boolean,
): ("receive" | "close")[] {
  if (!canComplete || orderStatus !== "IN_PROGRESS") return [];
  if (completion.receipt === null) return completion.receiveBlockers.length === 0 ? ["receive"] : [];
  return completion.closeBlockers.length === 0 ? ["close"] : [];
}


/** Shift days as the API holds them: Monday is 1, Tuesday 2 … Sunday 64. */
export const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;

export function daysToMask(days: readonly boolean[]): number {
  return days.reduce((mask, on, index) => (on ? mask | (1 << index) : mask), 0);
}

export function maskToDays(mask: number): boolean[] {
  return WEEKDAYS.map((_, index) => (mask & (1 << index)) !== 0);
}

export function describeDays(mask: number): string {
  if (mask === 127) return "every day";
  if (mask === 31) return "Mon–Fri";
  if (mask === 63) return "Mon–Sat";
  return WEEKDAYS.filter((_, index) => (mask & (1 << index)) !== 0).join(", ");
}

/** A date and time typed in the browser's own time zone, as an instant for the API. Null if either is missing. */
export function toInstant(date: unknown, time: unknown): string | null {
  if (typeof date !== "string" || typeof time !== "string" || !date || !time) return null;
  const local = new Date(`${date}T${time}`);
  return Number.isNaN(local.getTime()) ? null : local.toISOString();
}

/** "450.0" minutes reads as "7 h 30 min". */
export function formatMinutes(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "–";
  const total = Math.round(Number(value));
  const hours = Math.floor(total / 60);
  if (hours === 0) return `${total} min`;
  return total % 60 === 0 ? `${hours} h` : `${hours} h ${total % 60} min`;
}

/** A figure the server could not work out is shown as not known, never as zero. */
export function formatPercent(value: string | null | undefined): string {
  return value === null || value === undefined ? "Not known" : `${Number(value)}%`;
}

export function formatMoney(value: string | null | undefined, currency: string): string {
  if (value === null || value === undefined) return "Not known";
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(Number(value));
  } catch {
    return `${Number(value).toFixed(2)} ${currency}`;
  }
}

/** Load against capacity, as a share; null where there is no capacity at all. */
export function loadShare(day: { capacityMinutes: string; loadMinutes: string }): number | null {
  const capacity = Number(day.capacityMinutes);
  if (capacity <= 0) return Number(day.loadMinutes) > 0 ? Infinity : null;
  return Number(day.loadMinutes) / capacity;
}

/** Statuses an order can be rescheduled in: approved and not yet closed. Drafts change by editing. */
export function reschedulable(status: WorkOrderStatus): boolean {
  return ["APPROVED", "RELEASED", "IN_PROGRESS", "ON_HOLD"].includes(status);
}

/** A day in the browser's time zone, as YYYY-MM-DD, some days from today. */
export function localDate(offsetDays = 0, from: Date = new Date()): string {
  const day = new Date(from.getFullYear(), from.getMonth(), from.getDate() + offsetDays);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}
