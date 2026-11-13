import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

/**
 * Manufacturing phase 4, from the shop-floor screens: an operation started on a machine, quantities reported and
 * reconciled, a measurement out of limits, a breakdown, and completion.
 */
test("an operator runs an operation from the shop floor", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const pcs = units.find((unit) => unit.code === "PCS")!.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const product = await call("POST", "/products", { code: "GB-1", name: "Gearbox", productType: "FINISHED", uomId: pcs });

  // One operation on one machine, with the spindle held between 800 and 1200 rpm.
  await call("PUT", "/manufacturing/settings", {
    manufacturingModel: "DISCRETE",
    separateApprover: false,
    workOrderRequiresBom: false,
  });
  const centre = await call("POST", "/manufacturing/work-centers", { code: "WC-1", name: "Machining", facilityId: facility.id });
  const machine = await call("POST", "/manufacturing/machines", { code: "M-1", name: "Haas VF-2", workCenterId: centre.id });
  const routing = await call("POST", "/manufacturing/routings", { productId: product.id });
  const withOperation = await call("POST", `/manufacturing/routings/${routing.revision.id}/operations`, {
    sequence: 10,
    name: "Machine housing",
    workCenterId: centre.id,
    setupMinutes: "15",
    runMinutes: "2",
    instructions: "Clamp on the left datum.",
  });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/operations/${withOperation.operations[0].id}/parameters`, {
    code: "RPM",
    name: "Spindle speed",
    minimum: "800",
    maximum: "1200",
  });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/submit`);
  await call("POST", `/manufacturing/routings/${routing.revision.id}/approve`, {});

  const created = await call("POST", "/manufacturing/work-orders", {
    productId: product.id,
    facilityId: facility.id,
    quantity: "10",
    plannedStart: day(0),
    plannedEnd: day(2),
  });
  const order = `/manufacturing/work-orders/${created.workOrder.id}`;
  await call("POST", `${order}/submit`);
  await call("POST", `${order}/approve`, {});
  await call("POST", `${order}/release`);
  await call("POST", `${order}/start`);

  await page.goto("/manufacturing/shop-floor");
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Shop floor" })).toBeVisible();
  await page.getByRole("link", { name: /10\. Machine housing/ }).click();
  await expect(page.getByRole("heading", { name: "10. Machine housing" })).toBeVisible();
  await expect(page.getByText("Clamp on the left datum.")).toBeVisible();

  await step(page, "Start", async (dialog: Locator) => {
    await dialog.getByLabel("Machine").selectOption({ label: "M-1 – Haas VF-2" });
  });
  await expect(page.locator("[data-slot='badge']", { hasText: "in progress" }).first()).toBeVisible();

  // Good, rejected and scrap must make up what was processed.
  await step(page, "Report quantities", async (dialog: Locator) => {
    await dialog.getByLabel(/^Processed/).fill("10");
    await dialog.getByLabel("Good", { exact: true }).fill("9");
    await dialog.getByLabel("Rejected", { exact: true }).fill("1");
    await dialog.getByLabel("Of the rejected, to rework").fill("1");
  });
  const quantities = page.locator("[data-slot='card']").filter({ hasText: "Can come in" });
  await expect(quantities.getByText("9 PCS")).toBeVisible();
  await expect(quantities.getByText("1 PCS, 1 to rework")).toBeVisible();

  await step(page, "Record measurement", async (dialog: Locator) => {
    await dialog.getByLabel("Parameter").selectOption({ label: "RPM – Spindle speed" });
    await dialog.getByLabel("Value").fill("1300");
  });
  const parameters = page.locator("[data-slot='card']").filter({ hasText: "Parameters" });
  await expect(parameters.locator("[data-slot='badge']", { hasText: "fail" })).toBeVisible();

  // A breakdown stops the machine too; it has to be fixed before the operation runs again.
  await step(page, "Pause", async (dialog: Locator) => {
    await dialog.getByLabel("Why it stopped").selectOption({ label: "Machine breakdown" });
    await dialog.getByLabel("Details").fill("Coolant leak");
  });
  await expect(page.getByText("Machine breakdown: Coolant leak").first()).toBeVisible();
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.getByText("Machine M-1 is broken down.")).toBeVisible();
  await call("POST", `/manufacturing/machines/${machine.id}/status`, { status: "AVAILABLE" });
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.locator("[data-slot='badge']", { hasText: "in progress" }).first()).toBeVisible();

  await step(page, "Complete", async (dialog: Locator) => {
    await dialog.getByLabel(/Setup took/).fill("0");
  });
  await expect(page.locator("[data-slot='badge']", { hasText: "completed" }).first()).toBeVisible();

  const timeline = page.locator("[data-slot='card']").filter({ hasText: "Timeline" });
  await timeline.getByLabel("Comment").fill("Burr on the left face");
  await timeline.getByRole("button", { name: "Add" }).click();
  await expect(timeline.getByText("Burr on the left face")).toBeVisible();
  await expect(timeline.getByText("Machine breakdown")).toBeVisible();

  // The work order shows where its operations stand.
  await page.getByRole("link", { name: /^WO-/ }).click();
  const operations = page.locator("[data-slot='card']").filter({ hasText: "Operations" });
  await expect(operations.getByText(/Good 9 · rejected 1 · scrap 0 · waiting 0 PCS/)).toBeVisible();
});
