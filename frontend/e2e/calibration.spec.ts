import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { prepare, step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

test("an instrument is registered, calibrated and failed, and a failure shows in its record", async ({ page }) => {
  await signUp(page);
  await apiCaller(page)("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });

  await page.goto("/calibration");
  await step(page, "Register instrument", async (dialog: Locator) => {
    await dialog.getByLabel("Instrument ID").fill("VC-101");
    await dialog.getByLabel("Name").fill("Vernier caliper");
    await dialog.getByLabel("Category").fill("Dimensional");
    await dialog.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
    await dialog.getByLabel("Calibrate every (days)").fill("180");
    await dialog.getByLabel("Next calibration due").fill(day(-3));
  });
  await expect(page.getByRole("row", { name: /VC-101/ })).toContainText("overdue");
  await page.getByRole("link", { name: "VC-101" }).click();

  const qr = page.getByRole("img", { name: "QR code for VC-101" });
  await expect(qr).toBeVisible();
  await expect.poll(() => qr.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);

  await step(page, "Record calibration", async (dialog: Locator) => {
    await dialog.getByLabel("Calibrated on").fill(day(0));
    await dialog.getByLabel("Result").selectOption("PASS");
    await dialog.getByLabel("Performed by").fill("NABL Lab");
    await dialog.getByLabel("Certificate number").fill("C-77");
  });
  await expect(page.getByText(/Certificate C-77/)).toBeVisible();
  await expect(page.getByText("in date")).toBeVisible();

  await page.getByRole("button", { name: "Certificates" }).click();
  await page.getByLabel("Upload a document").setInputFiles({
    name: "certificate.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7 certificate"),
  });
  await expect(page.getByText("certificate.pdf")).toBeVisible();

  await step(page, "Record calibration", async (dialog: Locator) => {
    await dialog.getByLabel("Calibrated on").fill(day(0));
    await dialog.getByLabel("Result").selectOption("FAIL");
    await dialog.getByLabel("Performed by").fill("NABL Lab");
  });
  await expect(page.getByText("out of service").first()).toBeVisible();
});

test("a failed calibration raises an NCR and each inspection in doubt gets a decision", async ({ page }) => {
  test.setTimeout(60_000);
  await signUp(page);
  await prepare(page);
  const call = apiCaller(page);
  const facilities: { items: { id: string }[] } = await call("GET", "/facilities");
  const products: { items: { id: string }[] } = await call("GET", "/products");
  const lots: { itemId: string }[] = await call("GET", "/material-receipts/released-lots");
  const templates: { items: { id: string }[] } = await call("GET", "/inspection-templates");
  const instrument = await call("POST", "/instruments", {
    instrumentCode: "MIC-7",
    name: "Micrometer",
    category: "Dimensional",
    facilityId: facilities.items[0].id,
    frequencyDays: 180,
    nextDueOn: day(30),
  });
  await call("POST", `/instruments/${instrument.id}/calibrations`, {
    calibratedOn: day(-5),
    result: "PASS",
    performedBy: "NABL Lab",
  });
  const made = await call("POST", "/production-batches", {
    productId: products.items[0].id,
    facilityId: facilities.items[0].id,
    plannedQuantity: "10",
  });
  await call("POST", `/production-batches/${made.batch.id}/consume`, { receiptItemId: lots[0].itemId, quantity: "10" });
  await call("POST", `/production-batches/${made.batch.id}/complete`, { producedQuantity: "10" });
  const inspection = await call("POST", "/inspections", {
    productionBatchId: made.batch.id,
    templateId: templates.items[0].id,
  });
  await call("POST", `/inspections/${inspection.id}/instruments`, { instrumentIds: [instrument.id] });

  await page.goto(`/calibration/${instrument.id}`);
  await expect(page.getByText("No failed calibration has put an inspection in doubt.")).toBeVisible();
  await step(page, "Record calibration", async (dialog: Locator) => {
    await dialog.getByLabel("Calibrated on").fill(day(0));
    await dialog.getByLabel("Result").selectOption("FAIL");
    await dialog.getByLabel("Performed by").fill("NABL Lab");
  });
  await expect(page.getByText("1 waiting for a decision")).toBeVisible();
  await expect(page.getByRole("link", { name: /^NCR-/ })).toBeVisible();

  await step(page, "Decide", async (dialog: Locator) => {
    await dialog.getByLabel("Decision").selectOption("REINSPECT");
    await dialog.getByLabel("Why").fill("Drift exceeds the diameter tolerance");
  });
  await expect(page.getByText("all decided")).toBeVisible();

  await page.getByRole("link", { name: inspection.inspectionNumber, exact: true }).first().click();
  await expect(page.getByRole("heading", { name: inspection.inspectionNumber })).toBeVisible();
  await expect(page.getByText("reinspect", { exact: true })).toBeVisible();
});
