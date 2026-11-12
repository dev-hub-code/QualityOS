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

/**
 * The consume dialog offers lots and batches in one list, so each option carries its kind. Without a unit, the
 * quantity is in the lot's or batch's own.
 */
export function toConsumeRequest(source: string, quantity: string, uomId?: string) {
  const [kind, id] = source.split(":");
  const unit = uomId ? { uomId } : {};
  return kind === "lot" ? { receiptItemId: id, quantity, ...unit } : { parentBatchId: id, quantity, ...unit };
}

/** The dispatch form numbers its lines: productionBatchId1, quantity1, serials1, and so on. */
export function toDispatchItems(values: Record<string, unknown>, lines: number) {
  return Array.from({ length: lines }, (_, index) => {
    const line = index + 1;
    return {
      productionBatchId: values[`productionBatchId${line}`],
      quantity: values[`quantity${line}`],
      serialNumbers: parseSerials(String(values[`serials${line}`] ?? "")),
    };
  });
}

/**
 * The return form has a quantity and serials for each line that can still come back: quantity1, serials1, and so on.
 * Lines left blank are not part of the return.
 */
export function toReturnItems(values: Record<string, unknown>, lineIds: string[]) {
  return lineIds.flatMap((dispatchItemId, index) => {
    const line = index + 1;
    const quantity = String(values[`quantity${line}`] ?? "").trim();
    if (!quantity) return [];
    return [{ dispatchItemId, quantity, serialNumbers: parseSerials(String(values[`serials${line}`] ?? "")) }];
  });
}
