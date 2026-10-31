import { expect, type Browser, type Locator, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

export async function step(who: Page, button: string, fill: (dialog: Locator) => Promise<unknown>) {
  await who.getByRole("button", { name: button, exact: true }).click();
  const dialog = who.getByRole("dialog");
  await fill(dialog);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
}

/** A released lot, a product, a customer and a published final-inspection template, through the API. */
export async function prepare(page: Page) {
  const call = apiCaller(page);
  const today = new Date().toISOString().slice(0, 10);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const uomId = units.find((unit) => unit.code === "PCS")?.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId, inspectionRequired: false });
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const order = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: today,
    items: [{ materialId: material.id, quantity: "100" }],
  });
  await call("POST", `/purchase-orders/${order.id}/issue`);
  await call("POST", "/material-receipts", {
    purchaseOrderId: order.id,
    receivedOn: today,
    items: [{ purchaseOrderItemId: order.items[0].id, supplierBatch: "HEAT-7", quantity: "100" }],
  });
  await call("POST", "/products", { code: "SHAFT", name: "Drive Shaft", productType: "FINISHED", uomId });
  await call("POST", "/customers", { code: "TATA", name: "Tata Motors" });
  const created = await call("POST", "/inspection-templates", { code: "FINAL", name: "Final check", inspectionType: "FQC" });
  const template = created.template.id;
  const draft = created.versions[0].id;
  await call("PUT", `/inspection-templates/${template}/versions/${draft}`, {
    samplingType: "FIXED",
    sampleSize: 1,
    acceptanceNumber: 0,
    parameters: [
      {
        code: "DIA",
        name: "Diameter",
        dataType: "NUMERIC",
        criticality: "MAJOR",
        mandatory: true,
        perSample: true,
        evidenceRequired: false,
        minValue: "19.9",
        maxValue: "20.1",
      },
    ],
  });
  await call("POST", `/inspection-templates/${template}/publish`, { effectiveFrom: today });
}

/** A released batch of 10, dispatched in full but not yet confirmed as shipped. */
export async function dispatchedBatch(page: Page, browser: Browser, serialNumbers: string[] = []) {
  await signUp(page);
  await prepare(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);
  const owner = apiCaller(page);
  const boss = apiCaller(manager);
  const lots: { itemId: string }[] = await owner("GET", "/material-receipts/released-lots");
  const products: { items: { id: string }[] } = await owner("GET", "/products");
  const facilities: { items: { id: string }[] } = await owner("GET", "/facilities");
  const templates: { items: { id: string }[] } = await owner("GET", "/inspection-templates");
  const customers: { id: string }[] = await owner("GET", "/customers");
  const made = await owner("POST", "/production-batches", {
    productId: products.items[0].id,
    facilityId: facilities.items[0].id,
    plannedQuantity: "10",
  });
  const batchId = made.batch.id;
  await owner("POST", `/production-batches/${batchId}/consume`, { receiptItemId: lots[0].itemId, quantity: "10" });
  await owner("POST", `/production-batches/${batchId}/complete`, { producedQuantity: "10" });
  if (serialNumbers.length > 0) await owner("POST", `/production-batches/${batchId}/serials`, { serialNumbers });
  const inspection = await owner("POST", "/inspections", { productionBatchId: batchId, templateId: templates.items[0].id });
  await owner("POST", `/inspections/${inspection.id}/execute`, {
    results: [{ parameterCode: "DIA", sampleNumber: 1, valueNumeric: "20.0" }],
  });
  await owner("POST", `/inspections/${inspection.id}/submit`);
  await manager.goto("/dashboard");
  await boss("POST", `/inspections/${inspection.id}/approve`);
  await boss("POST", `/inspections/${inspection.id}/disposition`, { disposition: "ACCEPT" });
  await owner("POST", "/dispatches", {
    customerId: customers[0].id,
    dispatchedOn: new Date().toISOString().slice(0, 10),
    items: [{ productionBatchId: batchId, quantity: String(serialNumbers.length || 10), serialNumbers }],
  });

  return { batchId, batchNumber: made.batch.batchNumber as string, manager, facilityId: facilities.items[0].id };
}
