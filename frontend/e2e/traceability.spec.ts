import { expect, test, type Browser, type Locator, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

async function step(who: Page, button: string, fill: (dialog: Locator) => Promise<unknown>) {
  await who.getByRole("button", { name: button, exact: true }).click();
  const dialog = who.getByRole("dialog");
  await fill(dialog);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
}

/** A released lot, a product, a customer and a published final-inspection template, through the API. */
async function prepare(page: Page) {
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

test("raw material is traced through production and inspection to the customer", async ({ page, browser }) => {
  test.setTimeout(90_000);
  await signUp(page);
  await prepare(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);

  await page.goto("/production-batches");
  await step(page, "Start batch", async (dialog) => {
    await dialog.getByLabel("Product").selectOption({ label: "SHAFT – Drive Shaft" });
    await dialog.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
    await dialog.getByLabel("Planned quantity").fill("10");
  });
  await expect(page).toHaveURL(/production-batches\/.+/);
  const batchUrl = page.url();

  await step(page, "Record material used", async (dialog) => {
    await dialog.getByLabel("Lot or batch").selectOption({ index: 1 });
    await dialog.getByLabel("Quantity used").fill("20");
  });
  await expect(page.getByText("EN8 – EN8 Bar")).toBeVisible();
  await step(page, "Complete batch", (dialog) => dialog.getByLabel("Produced quantity").fill("10"));
  await step(page, "Add serial numbers", (dialog) =>
    dialog.getByLabel("Serial numbers (one per line)").fill("SN-1\nSN-2"),
  );
  await expect(page.getByText("SN-1, SN-2")).toBeVisible();

  await page.getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: "FINAL – Final check" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  await page.getByLabel("DIA sample 1").fill("20.0");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();

  await manager.goto(page.url());
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await step(manager, "Record disposition", (dialog) =>
    dialog.getByLabel("Disposition", { exact: true }).selectOption("ACCEPT"),
  );

  await page.goto(batchUrl);
  await expect(page.getByText("released", { exact: true }).first()).toBeVisible();

  await page.goto("/dispatches");
  await step(page, "Record dispatch", async (dialog) => {
    await dialog.getByLabel("Customer").selectOption({ label: "TATA – Tata Motors" });
    await dialog.getByLabel("Date").fill(new Date().toISOString().slice(0, 10));
    await dialog.getByRole("button", { name: "Add another batch" }).click();
    await dialog.getByLabel("Released batch 1").selectOption({ index: 1 });
    await dialog.getByLabel("Quantity 1").fill("2");
    await dialog.getByLabel("Serial numbers 1, if tracked (one per line)").fill("SN-1\nSN-2");
    await dialog.getByLabel("Released batch 2").selectOption({ index: 1 });
    await dialog.getByLabel("Quantity 2").fill("3");
  });
  await expect(page.getByText("Tata Motors")).toBeVisible();

  // Starting from the supplier's heat number, the trace reaches the customer.
  await page.goto("/traceability");
  await page.getByLabel("Batch, serial, receipt, material or product").fill("HEAT-7");
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByText("Supplier batch HEAT-7", { exact: false }).locator("..").getByRole("link").click();
  await expect(page.getByText("Customers who received this:")).toBeVisible();
  await expect(page.getByText("Tata Motors").first()).toBeVisible();
  await expect(page.getByRole("link", { name: /^PB-/ }).first()).toBeVisible();

  // And from a serial number, it reaches back to the supplier.
  await page.goto("/traceability");
  await page.getByLabel("Batch, serial, receipt, material or product").fill("SN-1");
  await page.getByRole("button", { name: "Search" }).click();
  await expect(page.getByText("Serial number SN-1")).toBeVisible();
  await page.getByRole("link", { name: /^PB-/ }).click();
  await expect(page.getByText("Bharat Steels", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("supplier batch HEAT-7", { exact: false }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: /Inspection|FQC inspection/ }).first()).toBeVisible();

  // A dispatch recorded by mistake is reversed. It stays on the trace, and the customer no longer counts as affected.
  const traceUrl = page.url();
  await page.goto("/dispatches");
  await step(page, "Reverse", (dialog) => dialog.getByLabel("Why this dispatch never really happened").fill("Wrong customer"));
  await expect(page.getByText("reversed", { exact: true })).toBeVisible();
  await page.goto(traceUrl);
  await expect(page.getByText("reversed: Wrong customer", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("Customers who received this:")).toHaveCount(0);
});

test("a wrong lot on a completed batch is corrected with approval", async ({ page, browser }) => {
  test.setTimeout(90_000);
  await signUp(page);
  await prepare(page);
  const call = apiCaller(page);
  const lots: { itemId: string }[] = await call("GET", "/material-receipts/released-lots");
  const products: { items: { id: string }[] } = await call("GET", "/products");
  const facilities: { items: { id: string }[] } = await call("GET", "/facilities");
  const created = await call("POST", "/production-batches", {
    productId: products.items[0].id,
    facilityId: facilities.items[0].id,
    plannedQuantity: "5",
  });
  await call("POST", `/production-batches/${created.batch.id}/consume`, { receiptItemId: lots[0].itemId, quantity: "5" });
  await call("POST", `/production-batches/${created.batch.id}/complete`, { producedQuantity: "5" });
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);

  await page.goto(`/production-batches/${created.batch.id}`);
  await page.getByRole("button", { name: "Request correction" }).click();
  await page.getByRole("dialog").getByLabel("Quantity actually used").fill("6");
  await page.getByRole("dialog").getByLabel("Why this entry is wrong").fill("Weighed twice");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page).toHaveURL(/material-corrections\/.+/);
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("Someone other than you must approve this.")).toBeVisible();

  await manager.goto(page.url());
  await step(manager, "Approve", (dialog) => dialog.getByLabel("Note").fill("Checked the scale log"));
  await expect(manager.getByText("applied", { exact: true }).first()).toBeVisible();

  await page.goto(`/production-batches/${created.batch.id}`);
  await expect(page.getByText("Reversed: Weighed twice")).toBeVisible();
});

/** A released batch of 10, dispatched in full but not yet confirmed as shipped. */
async function dispatchedBatch(page: Page, browser: Browser) {
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
    items: [{ productionBatchId: batchId, quantity: "10" }],
  });

  return { batchId, batchNumber: made.batch.batchNumber as string, manager };
}

test("returned goods stay out of stock until an inspection accepts them", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const { batchId, manager } = await dispatchedBatch(page, browser);

  await page.goto("/dispatches");
  await expect(page.getByRole("button", { name: "Record return" })).toHaveCount(0);
  await step(page, "Confirm shipment", (dialog) =>
    dialog.getByLabel("Shipped on").fill(new Date().toISOString().slice(0, 10)),
  );
  await expect(page.getByRole("button", { name: "Reverse", exact: true })).toHaveCount(0);
  await step(page, "Record return", async (dialog) => {
    await dialog.getByLabel("Line", { exact: true }).selectOption({ index: 1 });
    await dialog.getByLabel("Returned quantity").fill("4");
    await dialog.getByLabel("Return reference").fill("RMA-1");
    await dialog.getByLabel("Date received").fill(new Date().toISOString().slice(0, 10));
    await dialog.getByLabel("Reason for return").fill("Scratched in transit");
  });
  await expect(page.getByText("(4 returned)")).toBeVisible();
  await expect(page.getByRole("button", { name: "Reverse", exact: true })).toHaveCount(0);

  await page.goto(`/production-batches/${batchId}`);
  await expect(page.getByText("Available").locator("..")).toContainText("0");

  await page.goto("/dispatch-reversals");
  await page.getByRole("link", { name: /^RET-/ }).click();
  await page.getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: "FINAL – Final check" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  const returnInspection = page.url();
  await page.getByLabel("DIA sample 1").fill("20.0");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();

  await manager.goto(returnInspection);
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await step(manager, "Record disposition", (dialog) =>
    dialog.getByLabel("Disposition", { exact: true }).selectOption("ACCEPT"),
  );

  await page.goto(`/production-batches/${batchId}`);
  await expect(page.getByText("Available").locator("..")).toContainText("4");
});

test("an expected return waits for the goods, and the recall report shows what is still out", async ({
  page,
  browser,
}) => {
  test.setTimeout(90_000);
  const { batchNumber } = await dispatchedBatch(page, browser);
  const today = new Date().toISOString().slice(0, 10);
  const report = `/traceability/${encodeURIComponent(batchNumber)}`;

  await page.goto(report);
  await expect(page.getByText("Shipment not confirmed for:")).toBeVisible();

  await page.goto("/dispatches");
  await expect(page.getByText("awaiting shipment", { exact: false }).first()).toBeVisible();
  await step(page, "Confirm shipment", (dialog) => dialog.getByLabel("Shipped on").fill(today));
  await step(page, "Record return", async (dialog) => {
    await dialog.getByLabel("Line", { exact: true }).selectOption({ index: 1 });
    await dialog.getByLabel("Returned quantity").fill("3");
    await dialog.getByLabel("Return reference").fill("RMA-EXPECTED");
    await dialog.getByLabel("Date received or expected").fill(today);
    await dialog.getByLabel("Reason for return").fill("Wrong size");
    await dialog.getByLabel("Not here yet: the goods are on their way").click();
  });

  await page.goto("/dispatch-reversals");
  await page.getByRole("link", { name: /^RET-/ }).click();
  await expect(page.getByRole("button", { name: "Inspect" })).toHaveCount(0);
  await step(page, "Mark received", (dialog) => dialog.getByLabel("Date received").fill(today));
  await expect(page.getByRole("button", { name: "Inspect" })).toBeVisible();

  await page.goto(report);
  await page.getByRole("link", { name: "Recall report" }).click();
  await expect(page.getByRole("heading", { name: `Recall report: ${batchNumber}` })).toBeVisible();
  const row = page.getByRole("row").nth(1);
  await expect(row.getByRole("cell").nth(4)).toHaveText("3");
  await expect(row.getByRole("cell").nth(5)).toHaveText("7");
});
