import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

/**
 * Manufacturing phase 2, from the pages a planner and a production manager would use: a plan approved, a work order
 * raised from it, approved, released against the BOM in effect, started into a production batch, held and resumed.
 */
test("a work order raised from a plan is approved, released and started", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((candidate) => candidate.code === code)!.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: unit("KG"), inspectionRequired: false });
  const product = await call("POST", "/products", { code: "GB-1", name: "Gearbox", productType: "FINISHED", uomId: unit("PCS") });

  // A one-person organisation approves its own work; the BOM needs 2 kg of bar per gearbox, plus 10% scrap.
  await call("PUT", "/manufacturing/settings", { manufacturingModel: "DISCRETE", separateApprover: false });
  const bom = await call("POST", "/manufacturing/boms", { productId: product.id });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/lines`, {
    materialId: material.id,
    quantity: "2",
    uomId: unit("KG"),
    scrapPercent: "10",
  });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/submit`);
  await call("POST", `/manufacturing/boms/${bom.revision.id}/approve`, {});

  // 15 kg of bar in stock: not enough for ten gearboxes.
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const order = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: day(0),
    items: [{ materialId: material.id, quantity: "15" }],
  });
  await call("POST", `/purchase-orders/${order.id}/issue`);
  const receipt = await call("POST", "/material-receipts", {
    purchaseOrderId: order.id,
    receivedOn: day(0),
    items: [{ purchaseOrderItemId: order.items[0].id, supplierBatch: "HEAT-7", quantity: "15" }],
  });
  const lot: string = receipt.items[0].internalBatch;

  await page.goto("/manufacturing/production-plans");
  const menu = page.getByRole("navigation", { name: "Main" });
  await expect(menu.getByRole("link", { name: "Work orders" })).toBeVisible();
  await step(page, "New plan", async (dialog: Locator) => {
    await dialog.getByLabel("Name").fill("Week 46");
    await dialog.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
    await dialog.getByLabel("Period starts").fill(day(0));
    await dialog.getByLabel("Period ends").fill(day(10));
  });
  await expect(page.getByRole("heading", { name: /Week 46/ })).toBeVisible();
  await step(page, "Add line", async (dialog: Locator) => {
    await dialog.getByLabel("Product", { exact: true }).selectOption({ label: "GB-1 – Gearbox" });
    await dialog.getByLabel(/^Quantity/).fill("10");
    await dialog.getByLabel("Due").fill(day(7));
  });
  await page.getByRole("button", { name: "Approve plan" }).click();
  await expect(page.locator("[data-slot='badge']", { hasText: "approved" })).toBeVisible();

  // The order takes what is still to be ordered on the line.
  await step(page, "Raise work order", async (dialog: Locator) => {
    await expect(dialog.getByLabel(/^Quantity/)).toHaveValue("10");
    await dialog.getByLabel("Planned start").fill(day(2));
  });
  await expect(page.getByRole("heading", { name: /^WO-/ })).toBeVisible();
  await expect(page.getByText("Revision 1")).toBeVisible();

  await page.getByRole("button", { name: "Submit for approval" }).click();
  await step(page, "Approve", async () => {});
  await page.getByRole("button", { name: "Release" }).click();
  const materials = page.locator("[data-slot='card']").filter({ hasText: "Still to find" });
  await expect(materials.getByText("22 KG").first()).toBeVisible();
  await expect(page.getByText("is short by 7 KG")).toBeVisible();

  // The free lot is set aside for this order, and the order starts short, on the record.
  await page.getByRole("button", { name: "Reserve what is free" }).click();
  const reservations = page.locator("[data-slot='card']").filter({ hasText: "Reservations" });
  await expect(reservations.getByText(`lot ${lot}`)).toBeVisible();
  await page.getByRole("button", { name: "Start production" }).click();
  await expect(page.getByRole("link", { name: /^PB-/ })).toBeVisible();
  await expect(page.getByText(/Started short:/)).toBeVisible();

  // Issue the reserved bar, then give some back.
  await step(page, "Issue", async (dialog: Locator) => {
    await dialog.getByLabel("Lot").selectOption({ label: `${lot} (15 KG reserved)` });
    await dialog.getByLabel(/^Quantity/).fill("15");
  });
  const issued = page.locator("[data-slot='card']").filter({ hasText: "Issued to the batch" });
  await expect(issued.getByText(`from ${lot} · 15 KG`)).toBeVisible();
  await step(page, "Return", async (dialog: Locator) => {
    await dialog.getByLabel(/^Quantity/).fill("3");
    await dialog.getByLabel("Why it goes back").fill("Offcuts unused");
  });
  await expect(issued.getByText(`from ${lot} · 12 KG`)).toBeVisible();
  const movements = page.locator("[data-slot='card']").filter({ hasText: "Stock movements" });
  await expect(movements.getByText("Offcuts unused")).toBeVisible();

  await step(page, "Put on hold", async (dialog: Locator) => {
    await dialog.getByLabel("Why it is on hold").fill("Tooling check");
  });
  await expect(page.getByText("On hold: Tooling check")).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel order" })).toHaveCount(0);
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.locator("[data-slot='badge']", { hasText: "in progress" }).first()).toBeVisible();

  const history = page.locator("[data-slot='card']").filter({ hasText: "Status history" });
  for (const status of ["draft", "submitted", "approved", "released", "on hold"]) {
    await expect(history.getByText(status, { exact: true })).toBeVisible();
  }

  // The plan shows the order against its line.
  await page.getByRole("link", { name: /^PP-/ }).click();
  await expect(page.getByRole("link", { name: /^WO-/ })).toBeVisible();
});
