import type { ProductionBatch } from "@/lib/types";

/** Serial numbers are typed or pasted, separated by lines or commas. */
export function parseSerials(text: string): string[] {
  return [...new Set(text.split(/[\n,]/).map((serial) => serial.trim()).filter(Boolean))];
}

/** A batch's stage decides which inspection it can take. Null means it can take none. */
export function inspectionTypeFor(batch: Pick<ProductionBatch, "status" | "producedQuantity">): "IPQC" | "FQC" | null {
  if (batch.status === "IN_PROGRESS") return "IPQC";
  if (batch.status === "COMPLETED") return "FQC";
  if (batch.status === "ON_HOLD") return batch.producedQuantity === null ? "IPQC" : "FQC";
  return null;
}

/** The consume dialog offers lots and batches in one list, so each option carries its kind. */
export function toConsumeRequest(source: string, quantity: string) {
  const [kind, id] = source.split(":");
  return kind === "lot" ? { receiptItemId: id, quantity } : { parentBatchId: id, quantity };
}
