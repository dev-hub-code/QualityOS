import { expect, test, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";
import { step } from "./trace-setup";

/**
 * One lot's whole history, the way a factory would make it: two lots arrive against one order, one fails incoming
 * inspection and is released under a deviation, both go into a batch that is checked on the line and at the end,
 * the batch ships to two customers, part of it comes back, and a recall has to find all of it. Each module is used
 * from the page a person would use, so that a break in the hand-offs between them shows up here.
 */

const today = () => new Date().toISOString().slice(0, 10);

/** FJ-1 to FJ-n, one per line, as serial numbers are typed. */
const serials = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, index) => `FJ-${from + index}`).join("\n");

type Call = ReturnType<typeof apiCaller>;

async function publishTemplate(
  call: Call,
  code: string,
  name: string,
  inspectionType: string,
  parameter: { code: string; name: string; min: string; max: string },
) {
  const created = await call("POST", "/inspection-templates", { code, name, inspectionType });
  await call("PUT", `/inspection-templates/${created.template.id}/versions/${created.versions[0].id}`, {
    samplingType: "FIXED",
    sampleSize: 1,
    acceptanceNumber: 0,
    parameters: [
      {
        code: parameter.code,
        name: parameter.name,
        dataType: "NUMERIC",
        criticality: "MAJOR",
        mandatory: true,
        perSample: true,
        evidenceRequired: false,
        minValue: parameter.min,
        maxValue: parameter.max,
      },
    ],
  });
  await call("POST", `/inspection-templates/${created.template.id}/publish`, { effectiveFrom: today() });
}

/** Facility, material, supplier, one order, two lots against it, a product, two customers and three templates. */
async function stockTheFactory(page: Page) {
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const uomId = units.find((unit) => unit.code === "PCS")?.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId, inspectionRequired: true });
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const order = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: today(),
    items: [{ materialId: material.id, quantity: "100" }],
  });
  await call("POST", `/purchase-orders/${order.id}/issue`);
  for (const [supplierBatch, quantity] of [
    ["HEAT-A", "60"],
    ["HEAT-B", "40"],
  ]) {
    await call("POST", "/material-receipts", {
      purchaseOrderId: order.id,
      receivedOn: today(),
      items: [{ purchaseOrderItemId: order.items[0].id, supplierBatch, quantity }],
    });
  }
  await call("POST", "/products", { code: "SHAFT", name: "Drive Shaft", productType: "FINISHED", uomId });
  await call("POST", "/customers", { code: "TATA", name: "Tata Motors" });
  await call("POST", "/customers", { code: "MAH", name: "Mahindra" });
  await publishTemplate(call, "BAR-IQC", "Bar incoming", "IQC", { code: "DIA", name: "Diameter", min: "19.9", max: "20.1" });
  await publishTemplate(call, "LINE", "Line check", "IPQC", { code: "HARD", name: "Hardness", min: "180", max: "220" });
  await publishTemplate(call, "FINAL", "Final check", "FQC", { code: "DIA", name: "Diameter", min: "19.9", max: "20.1" });

  const waiting: { items: { itemId: string; internalBatch: string; quantity: string }[] } = await call(
    "GET",
    "/material-receipts/awaiting-inspection?size=50",
  );
  const lot = (quantity: number) => {
    const found = waiting.items.find((item) => Number(item.quantity) === quantity);
    if (!found) throw new Error(`No lot of ${quantity} is waiting for inspection`);
    return found;
  };
  return { lotA: lot(60), lotB: lot(40), orderNumber: order.poNumber as string };
}

/** Starts an inspection from the page that offers it, records one measurement and submits it. */
async function inspectHere(page: Page, templateLabel: string, measurement: string, value: string) {
  await page.getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: templateLabel });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  await page.getByLabel(measurement).fill(value);
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();
  return page.url();
}

/** The second person approves the result and decides what happens to the goods. */
async function decide(manager: Page, inspectionUrl: string, disposition: string, justification?: string) {
  await manager.goto(inspectionUrl);
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await step(manager, "Record disposition", async (dialog) => {
    await dialog.getByLabel("Disposition", { exact: true }).selectOption(disposition);
    if (justification) await dialog.getByLabel("Justification").fill(justification);
  });
  await expect(manager.getByText("completed", { exact: true }).first()).toBeVisible();
}

test("a lot keeps its history from the order through a conditional release, production, dispatch, return and recall", async ({
  page,
  browser,
}) => {
  test.setTimeout(240_000);
  await signUp(page);
  const { lotA, lotB } = await stockTheFactory(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);

  // Goods in to inspection: both lots from the order are waiting, under their own numbers.
  await page.goto("/inspections");
  const queued = (lot: { internalBatch: string }) => page.getByRole("listitem").filter({ hasText: lot.internalBatch });
  await expect(queued(lotA)).toContainText("Bharat Steels");
  await expect(queued(lotB)).toContainText("Bharat Steels");

  // Lot A passes.
  await queued(lotA).getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: "BAR-IQC – Bar incoming" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  await page.getByLabel("DIA sample 1").fill("20.0");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();
  await decide(manager, page.url(), "ACCEPT");

  // Lot B fails a major parameter. It is released under a deviation, which must be justified, and an NCR is raised.
  await page.goto("/inspections");
  await queued(lotB).getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: "BAR-IQC – Bar incoming" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  await page.getByLabel("DIA sample 1").fill("20.3");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();
  const lotBInspection = page.url();

  await manager.goto(lotBInspection);
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await manager.getByRole("button", { name: "Record disposition" }).click();
  const dialog = manager.getByRole("dialog");
  await dialog.getByLabel("Disposition", { exact: true }).selectOption("CONDITIONAL_ACCEPTANCE");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog.getByText("needs a justification")).toBeVisible();
  await dialog.getByLabel("Justification").fill("Within the customer drawing tolerance, used under deviation D-17");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(manager.getByText("completed", { exact: true }).first()).toBeVisible();
  await manager.getByRole("link", { name: /^NCR-/ }).click();
  await expect(manager).toHaveURL(/ncrs\//);
  const ncrUrl = manager.url();
  const ncrNumber = (await manager.getByRole("heading", { name: /^NCR-/ }).textContent())?.trim() ?? "";
  await expect(manager.getByText(lotB.internalBatch)).toBeVisible();
  await expect(manager.getByRole("link", { name: "Inspection", exact: true })).toBeVisible();

  // Production takes both lots, the released one and the conditionally released one.
  await page.goto("/production-batches");
  await step(page, "Start batch", async (form) => {
    await form.getByLabel("Product").selectOption({ label: "SHAFT – Drive Shaft" });
    await form.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
    await form.getByLabel("Planned quantity").fill("50");
  });
  await expect(page).toHaveURL(/production-batches\/.+/);
  const batchUrl = page.url();
  const batchNumber = (await page.getByRole("heading", { name: /^PB-/ }).textContent())?.trim() ?? "";
  for (const [lot, quantity] of [
    [lotA, "60"],
    [lotB, "40"],
  ] as const) {
    await step(page, "Record material used", async (form) => {
      await form.getByLabel("Lot or batch").selectOption(`lot:${lot.itemId}`);
      await form.getByLabel("Quantity used").fill(quantity);
    });
    await expect(page.getByText(lot.internalBatch).first()).toBeVisible();
  }

  // Checked on the line while it is still being made: an accepted in-process inspection keeps it running.
  const lineCheck = await inspectHere(page, "LINE – Line check", "HARD sample 1", "200");
  await decide(manager, lineCheck, "ACCEPT");
  await page.goto(batchUrl);
  await expect(page.getByText("in progress", { exact: true }).first()).toBeVisible();

  await step(page, "Complete batch", (form) => form.getByLabel("Produced quantity").fill("50"));
  await step(page, "Add serial numbers", (form) => form.getByLabel("Serial numbers (one per line)").fill(serials(1, 50)));

  // Nothing ships before the final inspection releases it.
  await page.goto("/dispatches");
  await page.getByRole("button", { name: "Record dispatch" }).click();
  await expect(page.getByRole("dialog").getByLabel("Released batch 1").locator("option")).toHaveCount(1);
  await page.keyboard.press("Escape");

  await page.goto(batchUrl);
  const finalCheck = await inspectHere(page, "FINAL – Final check", "DIA sample 1", "20.0");
  await decide(manager, finalCheck, "ACCEPT");
  await page.goto(batchUrl);
  await expect(page.getByText("released", { exact: true }).first()).toBeVisible();

  // Two customers get their share, and each shipment is confirmed on its own row.
  await page.goto("/dispatches");
  for (const [customer, quantity, units] of [
    ["TATA – Tata Motors", "30", serials(1, 30)],
    ["MAH – Mahindra", "20", serials(31, 50)],
  ]) {
    await step(page, "Record dispatch", async (form) => {
      await form.getByLabel("Customer").selectOption({ label: customer });
      await form.getByLabel("Date", { exact: true }).fill(today());
      await form.getByLabel("Released batch 1").selectOption({ index: 1 });
      await form.getByLabel("Quantity 1").fill(quantity);
      await form.getByLabel("Serial numbers 1, if tracked (one per line)").fill(units);
    });
  }
  for (const customer of ["Tata Motors", "Mahindra"]) {
    await page.getByRole("row", { name: new RegExp(customer) }).getByRole("button", { name: "Confirm shipment" }).click();
    await page.getByRole("dialog").getByLabel("Shipped on").fill(today());
    await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
  }
  await page.goto(batchUrl);
  await expect(page.getByText("Available", { exact: true }).locator("..")).toContainText("0");

  // Five come back from Tata. They count as stock again only once an inspection accepts them.
  await page.goto("/dispatches");
  await page.getByRole("row", { name: /Tata Motors/ }).getByRole("button", { name: "Record return" }).click();
  const returning = page.getByRole("dialog");
  await returning.getByLabel("Line", { exact: true }).selectOption({ index: 1 });
  await returning.getByLabel("Returned quantity").fill("5");
  await returning.getByLabel("Serial numbers, if tracked (one per line)").fill(serials(1, 5));
  await returning.getByLabel("Return reference").fill("RMA-9");
  await returning.getByLabel("Date received or expected").fill(today());
  await returning.getByLabel("Reason for return").fill("Scored on the journal");
  await returning.getByRole("button", { name: "Save" }).click();
  await expect(returning).toBeHidden();
  await expect(page.getByText("(5 returned)")).toBeVisible();

  await page.goto("/dispatch-reversals");
  await page.getByRole("link", { name: /^RET-/ }).click();
  const returnCheck = await inspectHere(page, "FINAL – Final check", "DIA sample 1", "20.0");
  await decide(manager, returnCheck, "ACCEPT");
  await page.goto(batchUrl);
  await expect(page.getByText("Available", { exact: true }).locator("..")).toContainText("5");

  // The trace from the lot released under deviation still reaches everything: its NCR, the batch, both customers
  // and the return.
  await page.goto(`/traceability/${encodeURIComponent(lotB.internalBatch)}`);
  await expect(page.getByRole("heading", { name: lotB.internalBatch })).toBeVisible();
  const received = page.getByText("Customers who received this:").locator("..");
  await expect(received).toContainText("Mahindra");
  await expect(received).toContainText("Tata Motors");
  await expect(page.getByRole("link", { name: batchNumber }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: ncrNumber })).toBeVisible();
  await expect(page.getByText("RMA-9")).toBeVisible();

  // A single unit is found by its serial number and knows which customer has it.
  await page.goto("/traceability");
  await page.getByLabel("Batch, serial, receipt, material or product").fill("FJ-40");
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByRole("link", { name: "FJ-40", exact: true }).click();
  const delivery = page.getByRole("listitem").filter({ hasText: "Mahindra on" });
  await expect(delivery).toContainText("shipped");
  await expect(delivery.getByRole("link", { name: /^DSP-/ })).toBeVisible();

  // The recall report counts what each customer still holds, and a recall names them both.
  await page.goto(`/traceability/${encodeURIComponent(batchNumber)}/recall`);
  await expect(page.getByRole("heading", { name: `Recall report: ${batchNumber}` })).toBeVisible();
  const tata = page.getByRole("row", { name: /Tata Motors/ });
  await expect(tata.getByRole("cell").nth(4)).toHaveText("5");
  await expect(tata.getByRole("cell").nth(5)).toHaveText("25");
  const mahindra = page.getByRole("row", { name: /Mahindra/ });
  await expect(mahindra.getByRole("cell").nth(4)).toHaveText("0");
  await expect(mahindra.getByRole("cell").nth(5)).toHaveText("20");
  await step(page, "Start a recall", async (form) => {
    await form.getByLabel("Recall date").fill(today());
    await form.getByLabel("Reason for recall").fill("Bar released under deviation D-17 found cracked in service");
    await form.getByLabel("What customers must do").fill("Quarantine and return every unit");
  });
  await expect(page.getByRole("heading", { name: /^RCL-/ })).toBeVisible();
  await expect(page.getByRole("listitem").getByText("Tata Motors")).toBeVisible();
  await expect(page.getByRole("listitem").getByText("Mahindra")).toBeVisible();

  // The NCR behind it is still open, and still points at the lot and its inspection.
  await page.goto(ncrUrl);
  await expect(page.getByText("open", { exact: true }).first()).toBeVisible();
  await expect(page.getByText(lotB.internalBatch)).toBeVisible();
  await expect(page.getByRole("link", { name: "Inspection", exact: true })).toBeVisible();
});
