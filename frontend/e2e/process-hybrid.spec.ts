import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const card = (page: Page, title: string) => page.locator("[data-slot='card']").filter({ hasText: title });

/** The status a request gets, as the signed-in user, for checks the API caller cannot make. */
function statusOf(page: Page, method: string, path: string, data: unknown = {}) {
  return page.evaluate(
    async ([method, path, data]) => {
      const init = method === "GET" ? {} : { method, headers: { "content-type": "application/json" }, body: JSON.stringify(data) };
      return (await fetch(`/api/v1${path}`, init)).status;
    },
    [method, path, data] as const,
  );
}

/** A facility, an approved supplier and released stock of each material, through the API. */
async function plantWithStock(page: Page, stock: { materialId: string; quantity: string }[]) {
  const call = apiCaller(page);
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Asian Chemicals" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const purchase = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: day(0),
    items: stock,
  });
  await call("POST", `/purchase-orders/${purchase.id}/issue`);
  const receipt = await call("POST", "/material-receipts", {
    purchaseOrderId: purchase.id,
    receivedOn: day(0),
    items: purchase.items.map((item: { id: string }, index: number) => ({
      purchaseOrderItemId: item.id,
      supplierBatch: `B-${index + 1}`,
      quantity: stock[index].quantity,
    })),
  });
  const lots: string[] = receipt.items.map((item: { id: string }) => item.id);
  return { facility, lots };
}

/** A work order through approval, release and start, through the API. */
async function startedOrder(page: Page, productId: string, facilityId: string, quantity: string) {
  const call = apiCaller(page);
  const order = await call("POST", "/manufacturing/work-orders", {
    productId,
    facilityId,
    quantity,
    plannedStart: day(0),
    plannedEnd: day(2),
  });
  const path = `/manufacturing/work-orders/${order.workOrder.id}`;
  await call("POST", `${path}/submit`);
  await call("POST", `${path}/approve`, {});
  await call("POST", `${path}/release`);
  await call("POST", `${path}/start`);
  return { id: order.workOrder.id as string, number: order.workOrder.workOrderNumber as string, path };
}

/**
 * Process manufacturing, from the screens: a recipe with a proportional line carrying scrap, a fixed line and a process
 * parameter is scaled and approved; a work order needs the scaled, scrap and fixed quantities; less is made than
 * ordered, and the fixed line is still needed in full, so no variance reason is asked for.
 */
test("a process product is made to a recipe and closed short", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((found) => found.code === code)!.id;
  const resin = await call("POST", "/materials", { code: "RES", name: "Resin", uomId: unit("KG"), inspectionRequired: false });
  const catalyst = await call("POST", "/materials", { code: "CAT", name: "Catalyst", uomId: unit("KG"), inspectionRequired: false });
  const paint = await call("POST", "/products", { code: "PNT-1", name: "Enamel Paint", productType: "FINISHED", uomId: unit("KG") });
  await call("PUT", "/manufacturing/settings", {
    manufacturingModel: "PROCESS",
    separateApprover: false,
    serialTracking: false,
    completionRequiresFinalInspection: false,
  });
  const { facility, lots } = await plantWithStock(page, [
    { materialId: resin.id, quantity: "300" },
    { materialId: catalyst.id, quantity: "2" },
  ]);

  // The recipe makes 100 kg: 95 kg of resin with 2% scrap, and 500 g of catalyst whatever the batch.
  await page.goto("/manufacturing/recipes");
  await step(page, "New recipe", async (dialog: Locator) => {
    await dialog.getByLabel("Product", { exact: true }).selectOption({ label: "PNT-1 – Enamel Paint" });
    await dialog.getByLabel("Batch size, in the product's unit").fill("100");
  });
  await step(page, "Add line", async (dialog: Locator) => {
    await dialog.getByLabel("Material or product").selectOption({ label: "Material: RES – Resin" });
    await dialog.getByLabel(/^Quantity/).fill("95");
    await dialog.getByLabel("Unit").selectOption(unit("KG"));
    await dialog.getByLabel("Scrap allowance (%)").fill("2");
  });
  await step(page, "Add line", async (dialog: Locator) => {
    await dialog.getByLabel("Material or product").selectOption({ label: "Material: CAT – Catalyst" });
    await dialog.getByLabel(/^Quantity/).fill("500");
    await dialog.getByLabel("Unit").selectOption(unit("GM"));
    await dialog.getByLabel("Fixed quantity, whatever the batch size").check();
  });
  await expect(page.getByText("(fixed)")).toBeVisible();
  await step(page, "Add parameter", async (dialog: Locator) => {
    await dialog.getByLabel("Code").fill("TEMP");
    await dialog.getByLabel("Name").fill("Reactor temperature");
    await dialog.getByLabel("Unit, such as °C or bar").fill("°C");
    await dialog.getByLabel("Target").fill("180");
    await dialog.getByLabel("Lower limit").fill("175");
    await dialog.getByLabel("Upper limit").fill("185");
  });
  await expect(page.getByText("Target 180, 175 to 185 °C")).toBeVisible();

  // Scaled to 250 kg: resin 237.5 kg, 242.25 kg with its scrap; catalyst stays 500 g.
  const scale = card(page, "Scale to a batch");
  await scale.getByLabel(/^Batch size/).fill("250");
  await scale.getByRole("button", { name: "Show" }).click();
  await expect(scale.getByText("237.5 KG")).toBeVisible();
  await expect(scale.getByText("(242.25 KG with scrap allowance)")).toBeVisible();
  await expect(scale.getByText("500 GM")).toBeVisible();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await step(page, "Approve", async () => {});
  await expect(page.locator("[data-slot='badge']", { hasText: "in effect" })).toBeVisible();

  // A 250 kg order needs the scaled resin with scrap, and the catalyst in the material's own unit.
  const order = await startedOrder(page, paint.id, facility.id, "250");
  await page.goto(order.path);
  const materials = card(page, "Materials");
  await expect(materials.getByRole("row", { name: /RES – Resin/ })).toContainText("242.25 KG");
  await expect(materials.getByRole("row", { name: /CAT – Catalyst/ })).toContainText("0.5 KG");

  // 200 kg are made: the resin it needed (242.25 × 200 ÷ 250 = 193.8 kg) and all of the catalyst are issued.
  await call("POST", `${order.path}/issues`, { lineNumber: 1, lotId: lots[0], quantity: "193.8" });
  await call("POST", `${order.path}/issues`, { lineNumber: 2, lotId: lots[1], quantity: "0.5" });
  await page.reload();
  const completion = card(page, "Completion");
  await step(page, "Receive finished goods", async (dialog: Locator) => {
    await dialog.getByLabel("Quantity made (KG)").fill("200");
    await dialog.getByLabel(/^Why it is short/).fill("One drum short of resin charge");
  });
  await expect(completion.getByText("Received 200 of 250 KG")).toBeVisible();
  await expect(completion.getByText("Material variance:")).toBeHidden();
  await step(page, "Close short", async (dialog: Locator) => {
    await dialog.getByLabel("Why it closes short").fill("Short charge");
  });
  await expect(page.locator("[data-slot='badge']", { hasText: "partially completed" }).first()).toBeVisible();

  // The batch says it was made to the recipe.
  await page.getByRole("link", { name: /^PB-/ }).first().click();
  await expect(card(page, "Production record").getByText("Recipe revision 1")).toBeVisible();
});

/**
 * A hybrid organisation, from the screens: each product is set to be made one way, takes only that kind of definition,
 * and its work order is made from it.
 */
test("a hybrid organisation makes one product from a BOM and another to a recipe", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((found) => found.code === code)!.id;
  const steel = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: unit("KG"), inspectionRequired: false });
  const resin = await call("POST", "/materials", { code: "RES", name: "Resin", uomId: unit("KG"), inspectionRequired: false });
  const shaft = await call("POST", "/products", { code: "SH-1", name: "Drive Shaft", productType: "FINISHED", uomId: unit("PCS") });
  const paint = await call("POST", "/products", { code: "PNT-1", name: "Enamel Paint", productType: "FINISHED", uomId: unit("KG") });
  await call("PUT", "/manufacturing/settings", {
    manufacturingModel: "HYBRID",
    separateApprover: false,
    serialTracking: false,
    completionRequiresFinalInspection: false,
  });
  const { facility } = await plantWithStock(page, [
    { materialId: steel.id, quantity: "10" },
    { materialId: resin.id, quantity: "100" },
  ]);

  // Until a product is set to be made one way, it takes neither a BOM nor a recipe.
  await page.goto("/manufacturing/boms");
  await page.getByRole("button", { name: "New BOM", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Product", { exact: true }).selectOption({ label: "SH-1 – Drive Shaft" });
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog.getByText("Choose whether SH-1 is made by discrete or process manufacturing first.")).toBeVisible();
  await dialog.getByRole("button", { name: "Close" }).click();

  // On each product's page: the shaft is discrete, the paint process.
  for (const [product, model] of [
    [shaft, "Discrete – built from a BOM"],
    [paint, "Process – made to a recipe"],
  ] as const) {
    await page.goto(`/products/${product.id}`);
    await expect(page.getByText("Not chosen yet. Your organisation makes both kinds.")).toBeVisible();
    await step(page, "Set how it is made", async (form: Locator) => {
      await form.getByLabel("Manufacturing model").selectOption({ label: model });
    });
    await expect(card(page, "Manufacturing").getByText(model)).toBeVisible();
  }

  // Each takes only its own kind.
  expect(await statusOf(page, "POST", "/manufacturing/recipes", { productId: shaft.id })).toBe(422);
  expect(await statusOf(page, "POST", "/manufacturing/boms", { productId: paint.id })).toBe(422);
  const bom = await call("POST", "/manufacturing/boms", { productId: shaft.id });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/lines`, { materialId: steel.id, quantity: "1", uomId: unit("KG") });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/submit`);
  await call("POST", `/manufacturing/boms/${bom.revision.id}/approve`, {});
  const recipe = await call("POST", "/manufacturing/recipes", { productId: paint.id, baseQuantity: "100" });
  await call("POST", `/manufacturing/recipes/${recipe.revision.id}/lines`, { materialId: resin.id, quantity: "95", uomId: unit("KG") });
  await call("POST", `/manufacturing/recipes/${recipe.revision.id}/submit`);
  await call("POST", `/manufacturing/recipes/${recipe.revision.id}/approve`, {});

  // Both menus are there, and each work order is made from its product's own definition.
  const menu = page.getByRole("navigation");
  await expect(menu.getByRole("link", { name: "BOMs" })).toBeVisible();
  await expect(menu.getByRole("link", { name: "Recipes" })).toBeVisible();
  for (const [product, quantity, needed, made] of [
    [shaft, "5", "5 KG", "BOM revision 1"],
    [paint, "50", "47.5 KG", "Recipe revision 1"],
  ] as const) {
    const order = await startedOrder(page, product.id, facility.id, quantity);
    await page.goto(order.path);
    await expect(card(page, "Materials").getByRole("row").nth(1)).toContainText(needed);
    await page.getByRole("link", { name: /^PB-/ }).first().click();
    await expect(card(page, "Production record").getByText(made)).toBeVisible();
  }
});

/**
 * Two organisations at once: what one makes, the other can neither see in its lists, open by address nor change
 * through the API, and each keeps its own numbering.
 */
test("two organisations make goods side by side without seeing each other's", async ({ page, browser }) => {
  test.setTimeout(90_000);
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const kg = units.find((found) => found.code === "KG")!.id;
  const pcs = units.find((found) => found.code === "PCS")!.id;
  const steel = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: kg, inspectionRequired: false });
  const shaft = await call("POST", "/products", { code: "SH-1", name: "Drive Shaft", productType: "FINISHED", uomId: pcs });
  await call("PUT", "/manufacturing/settings", { manufacturingModel: "DISCRETE", separateApprover: false });
  const { facility, lots } = await plantWithStock(page, [{ materialId: steel.id, quantity: "10" }]);
  const bom = await call("POST", "/manufacturing/boms", { productId: shaft.id });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/lines`, { materialId: steel.id, quantity: "1", uomId: kg });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/submit`);
  await call("POST", `/manufacturing/boms/${bom.revision.id}/approve`, {});
  const order = await startedOrder(page, shaft.id, facility.id, "5");
  const detail = await call("GET", order.path);
  const batchId: string = detail.workOrder.productionBatchId;

  // The other organisation, signed in in its own browser, makes the same kind of thing.
  const context = await browser.newContext();
  const stranger = await context.newPage();
  await signUp(stranger);
  const theirs = apiCaller(stranger);
  const theirUnits: { id: string; code: string }[] = await theirs("GET", "/units");
  const theirPcs = theirUnits.find((found) => found.code === "PCS")!.id;
  const theirShaft = await theirs("POST", "/products", { code: "SH-1", name: "Their Shaft", productType: "FINISHED", uomId: theirPcs });
  await theirs("PUT", "/manufacturing/settings", { manufacturingModel: "DISCRETE", separateApprover: false, workOrderRequiresBom: false });
  const theirPlant = await theirs("POST", "/facilities", { code: "PUNE", name: "Their Plant" });
  const theirOrder = await startedOrder(stranger, theirShaft.id, theirPlant.id, "3");
  // Numbering is each organisation's own, so both start at the same number.
  expect(theirOrder.number).toBe(order.number);

  // Their lists show only theirs; our records' addresses do not exist for them.
  await stranger.goto("/manufacturing/work-orders");
  await expect(stranger.getByText("Their Shaft").first()).toBeVisible();
  await expect(stranger.getByText("Drive Shaft")).toBeHidden();
  for (const address of [order.path, `/manufacturing/boms/${bom.revision.id}`, `/production-batches/${batchId}`]) {
    await stranger.goto(address);
    await expect(stranger.getByText("This record does not exist.")).toBeVisible();
  }
  expect(await statusOf(stranger, "GET", `${order.path}/materials`)).toBe(404);
  expect(await statusOf(stranger, "POST", `${order.path}/issues`, { lineNumber: 1, lotId: lots[0], quantity: "1" })).toBe(404);
  expect(await statusOf(stranger, "POST", `${order.path}/hold`, { reason: "Not yours" })).toBe(404);
  await context.close();

  // Nothing they tried reached our order: no material was issued to it.
  const after = await call("GET", `${order.path}/materials`);
  expect(after.consumptions).toHaveLength(0);
  expect(after.lines[0].issued).toMatch(/^0(\.0+)?$/);
});
