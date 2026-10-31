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
