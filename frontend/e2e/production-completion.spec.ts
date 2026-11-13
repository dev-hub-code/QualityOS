import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const badge = (where: Page | Locator, text: string) => where.locator("[data-slot='badge']", { hasText: text }).first();

/**
 * Manufacturing phase 6, from the screens: a finished work order's goods are received into its batch with serial
 * numbers, a short reason and a material variance reason, the order is closed short, and the batch shows how it was
 * made.
 */
test("finished goods are received and a work order is closed short", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((found) => found.code === code)!.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: unit("KG"), inspectionRequired: false });
  const product = await call("POST", "/products", { code: "SH-1", name: "Drive Shaft", productType: "FINISHED", uomId: unit("PCS") });

  // A one-person organisation approves its own work. Shafts are serialised and closing does not wait for inspection.
  await page.goto("/manufacturing/settings");
  await step(page, "Change", async (dialog) => {
    await dialog.getByLabel("Discrete products are received with a serial number for each unit").check();
    await dialog.getByLabel("Closing a work order waits for its batch's final inspection").uncheck();
  });
  await expect(page.getByText("One for each unit of a discrete product")).toBeVisible();
  await call("PUT", "/manufacturing/settings", {
    manufacturingModel: "DISCRETE",
    separateApprover: false,
    serialTracking: true,
    completionRequiresFinalInspection: false,
  });
  const bom = await call("POST", "/manufacturing/boms", { productId: product.id });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/lines`, { materialId: material.id, quantity: "1", uomId: unit("KG") });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/submit`);
  await call("POST", `/manufacturing/boms/${bom.revision.id}/approve`, {});
  const centre = await call("POST", "/manufacturing/work-centers", { code: "WC-1", name: "Turning", facilityId: facility.id });
  const routing = await call("POST", "/manufacturing/routings", { productId: product.id });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/operations`, {
    sequence: 10,
    name: "Turn shaft",
    workCenterId: centre.id,
    setupMinutes: "5",
    runMinutes: "1",
  });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/submit`);
  await call("POST", `/manufacturing/routings/${routing.revision.id}/approve`, {});

  // 10 kg of bar in stock.
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const purchase = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: day(0),
    items: [{ materialId: material.id, quantity: "10" }],
  });
  await call("POST", `/purchase-orders/${purchase.id}/issue`);
  const receipt = await call("POST", "/material-receipts", {
    purchaseOrderId: purchase.id,
    receivedOn: day(0),
    items: [{ purchaseOrderItemId: purchase.items[0].id, supplierBatch: "HEAT-7", quantity: "10" }],
  });

  // Five shafts are ordered and 5 kg issued; turning makes four good and scraps one.
  const order = await call("POST", "/manufacturing/work-orders", {
    productId: product.id,
    facilityId: facility.id,
    quantity: "5",
    plannedStart: day(0),
    plannedEnd: day(2),
  });
  const orderPath = `/manufacturing/work-orders/${order.workOrder.id}`;
  await call("POST", `${orderPath}/submit`);
  await call("POST", `${orderPath}/approve`, {});
  await call("POST", `${orderPath}/release`);
  await call("POST", `${orderPath}/start`);
  await call("POST", `${orderPath}/issues`, { lineNumber: 1, lotId: receipt.items[0].id, quantity: "5" });
  const released = await call("GET", orderPath);
  const operation = `/manufacturing/operations/${released.operations[0].id}`;

  // Receiving waits for the operation.
  await page.goto(orderPath);
  const completion = page.locator("[data-slot='card']").filter({ hasText: "Completion" });
  await expect(completion.getByText("Operation 10 is ready.")).toBeVisible();
  await expect(completion.getByRole("button", { name: "Receive finished goods" })).toBeHidden();

  await call("POST", `${operation}/start`, {});
  await call("POST", `${operation}/reports`, {
    quantity: "5",
    goodQuantity: "4",
    rejectedQuantity: "0",
    scrapQuantity: "1",
    reworkQuantity: "0",
  });
  await call("POST", `${operation}/complete`, { reason: "One scrapped" });

  // Four are received, with a serial number each, and the reasons the server asks for.
  await page.reload();
  await expect(completion.getByText("good so far 4 PCS")).toBeVisible();
  await step(page, "Receive finished goods", async (dialog) => {
    await dialog.getByLabel(/^Why it is short/).fill("One scrapped at turning");
    await dialog.getByLabel("Why material use was off").fill("Bar for the scrapped shaft");
    await dialog.getByLabel("Serial numbers, one per line").fill("SH-0001\nSH-0002\nSH-0003\nSH-0004");
  });
  await expect(completion.getByText("Received 4 of 5 PCS")).toBeVisible();
  await expect(completion.getByText("Short: One scrapped at turning")).toBeVisible();
  await expect(completion.getByText(/4 serial numbers/)).toBeVisible();

  // It closes short, with a reason.
  await step(page, "Close short", async (dialog) => {
    await dialog.getByLabel("Why it closes short").fill("Scrap at turning");
  });
  await expect(page.getByText("Closed short: Scrap at turning")).toBeVisible();
  await expect(badge(page, "partially completed")).toBeVisible();
  await expect(completion.getByRole("button", { name: "Close short" })).toBeHidden();

  // The batch is finished goods, serialised, and shows how it was made.
  await page.getByRole("link", { name: /^PB-/ }).first().click();
  await expect(page.getByText("SH-0001, SH-0002, SH-0003, SH-0004")).toBeVisible();
  await expect(page.getByRole("button", { name: "Complete batch" })).toBeHidden();
  const record = page.locator("[data-slot='card']").filter({ hasText: "Production record" });
  await expect(record.getByRole("link", { name: /^WO-/ })).toBeVisible();
  await expect(record.getByText("BOM revision 1")).toBeVisible();
  await expect(record.getByText(/Good 4 · rejected 0 · scrapped 1/)).toBeVisible();
});
