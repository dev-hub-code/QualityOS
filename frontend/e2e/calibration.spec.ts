import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

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
