import { expect, test } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

/**
 * Manufacturing phase 7, from the screens: a shift and work centre rates are set, the scheduling board shows an
 * overloaded day and a reschedule clears it, downtime is recorded by hand and summarised, a work order shows its cost
 * with what is missing, and production analytics shows OEE.
 */
test("capacity, downtime, cost and OEE come from what was planned and recorded", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((found) => found.code === code)!.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: unit("KG"), inspectionRequired: false });
  const product = await call("POST", "/products", { code: "SH-1", name: "Drive Shaft", productType: "FINISHED", uomId: unit("PCS") });
  await call("PUT", "/manufacturing/settings", {
    manufacturingModel: "DISCRETE",
    separateApprover: false,
    serialTracking: false,
    completionRequiresFinalInspection: false,
  });

  // The plant works 08:00 to 16:00 every day with a half-hour break: 450 minutes.
  await page.goto(`/facilities/${facility.id}`);
  await step(page, "Add shift", async (dialog) => {
    await dialog.getByLabel("Name").fill("Day");
    await dialog.getByLabel("Starts at").fill("08:00");
    await dialog.getByLabel("Ends at").fill("16:00");
    await dialog.getByLabel("Break, in minutes").fill("30");
  });
  await expect(page.getByText("Day (08:00–16:00, every day, 30 min break)")).toBeVisible();

  const bom = await call("POST", "/manufacturing/boms", { productId: product.id });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/lines`, { materialId: material.id, quantity: "1", uomId: unit("KG") });
  await call("POST", `/manufacturing/boms/${bom.revision.id}/submit`);
  await call("POST", `/manufacturing/boms/${bom.revision.id}/approve`, {});
  const centre = await call("POST", "/manufacturing/work-centers", { code: "WC-1", name: "Turning", facilityId: facility.id });
  const machine = await call("POST", "/manufacturing/machines", { code: "M-1", name: "Lathe", workCenterId: centre.id });

  // Labour and overhead rates are set on the work centre; the machine rate is left out.
  await page.goto(`/manufacturing/work-centers/${centre.id}`);
  await step(page, "Edit", async (dialog) => {
    await dialog.getByLabel("Labour cost per hour").fill("600");
    await dialog.getByLabel("Overhead per hour").fill("120");
  });
  await expect(page.getByText("One operation per active machine")).toBeVisible();
  await expect(page.getByText("Not set")).toBeVisible();

  const routing = await call("POST", "/manufacturing/routings", { productId: product.id });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/operations`, {
    sequence: 10,
    name: "Turn shaft",
    workCenterId: centre.id,
    setupMinutes: "0",
    runMinutes: "60",
  });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/submit`);
  await call("POST", `/manufacturing/routings/${routing.revision.id}/approve`, {});

  // Ten shafts tomorrow need 600 minutes on one machine; the board shows the conflict.
  const big = await call("POST", "/manufacturing/work-orders", {
    productId: product.id,
    facilityId: facility.id,
    quantity: "10",
    plannedStart: day(1),
    plannedEnd: day(1),
  });
  const bigPath = `/manufacturing/work-orders/${big.workOrder.id}`;
  await call("POST", `${bigPath}/submit`);
  await call("POST", `${bigPath}/approve`, {});
  await page.goto("/manufacturing/scheduling");
  await expect(page.getByText("10 h / 7 h 30 min")).toBeVisible();
  const conflicts = page.locator("[data-slot='card']").filter({ hasText: "Conflicts" });
  await expect(conflicts.getByText(/WC-1 on .*: 10 h planned, 7 h 30 min available/)).toBeVisible();

  // Spread over two days, it fits.
  await step(page, "Reschedule", async (dialog) => {
    await dialog.getByLabel("Planned end").fill(day(2));
    await dialog.getByLabel("Why it moves").fill("Spread over two shifts");
  });
  await expect(page.getByRole("status").getByText(/rescheduled\. It fits\./)).toBeVisible();
  await expect(conflicts).toBeHidden();

  // A power failure is recorded by hand and summarised.
  await page.goto("/manufacturing/downtime");
  await step(page, "Record downtime", async (dialog) => {
    await dialog.getByLabel("Or work centre, if no one machine stopped").selectOption({ label: "WC-1 – Turning" });
    await dialog.getByLabel("Category").selectOption({ label: "Power failure" });
    await dialog.getByLabel("Start date").fill(day(0));
    await dialog.getByLabel("Start time").fill("00:00");
    await dialog.getByLabel("End (leave blank while it lasts) date").fill(day(0));
    await dialog.getByLabel("End (leave blank while it lasts) time").fill("00:20");
    await dialog.getByLabel("Reason").fill("Grid outage");
    await dialog.getByLabel("Responsible department").fill("Electrical");
  });
  const stop = page.locator("[data-slot='table-row']").filter({ hasText: "Grid outage" });
  await expect(stop.getByRole("cell", { name: "20 min", exact: true })).toBeVisible();
  await expect(stop.getByRole("cell", { name: "Electrical" })).toBeVisible();
  await page.getByLabel("By").selectOption("CATEGORY");
  const summary = page.locator("[data-slot='card']").filter({ hasText: "Summary" });
  await expect(summary.getByRole("cell", { name: "Power failure" })).toBeVisible();

  // Two shafts are made from priced bar: one good, one scrapped.
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const purchase = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: day(0),
    items: [{ materialId: material.id, quantity: "10", unitPrice: "50" }],
  });
  await call("POST", `/purchase-orders/${purchase.id}/issue`);
  const receipt = await call("POST", "/material-receipts", {
    purchaseOrderId: purchase.id,
    receivedOn: day(0),
    items: [{ purchaseOrderItemId: purchase.items[0].id, supplierBatch: "HEAT-7", quantity: "10" }],
  });
  const small = await call("POST", "/manufacturing/work-orders", {
    productId: product.id,
    facilityId: facility.id,
    quantity: "2",
    plannedStart: day(0),
    plannedEnd: day(2),
  });
  const smallPath = `/manufacturing/work-orders/${small.workOrder.id}`;
  await call("POST", `${smallPath}/submit`);
  await call("POST", `${smallPath}/approve`, {});
  await call("POST", `${smallPath}/release`);
  await call("POST", `${smallPath}/start`);
  await call("POST", `${smallPath}/issues`, { lineNumber: 1, lotId: receipt.items[0].id, quantity: "2" });
  const released = await call("GET", smallPath);
  const operation = `/manufacturing/operations/${released.operations[0].id}`;
  await call("POST", `${operation}/start`, { machineId: machine.id });
  await call("POST", `${operation}/reports`, {
    quantity: "2",
    goodQuantity: "1",
    rejectedQuantity: "0",
    scrapQuantity: "1",
    reworkQuantity: "0",
  });
  await call("POST", `${operation}/complete`, { reason: "One scrapped" });

  // The cost says what it does not know.
  await page.goto(smallPath);
  const cost = page.locator("[data-slot='card']").filter({ hasText: "Cost" }).filter({ hasText: "Planned" });
  await expect(cost.getByText("Work centre WC-1 has no machine rate.")).toBeVisible();
  await expect(cost.getByRole("row", { name: /^Material/ })).toContainText("100.00");

  // Analytics: quality from the report, OEE for the machine, figures without data marked as not known.
  await page.goto("/manufacturing/analytics");
  await expect(page.getByRole("heading", { name: "Production analytics" })).toBeVisible();
  const oee = page.locator("[data-slot='card']").filter({ hasText: "OEE by machine" });
  const lathe = oee.getByRole("row").filter({ hasText: "M-1 · WC-1" });
  await expect(lathe.getByRole("cell", { name: "50%", exact: true })).toBeVisible();
  await expect(page.getByText("Scrap rate")).toBeVisible();
  await expect(page.locator("[data-slot='card']").filter({ hasText: "Downtime by category" }).getByTitle("Power failure")).toBeVisible();
});
