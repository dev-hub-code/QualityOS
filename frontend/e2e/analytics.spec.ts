import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";
import { dispatchedBatch } from "./trace-setup";

test("inspection results reach the dashboard, analytics and exported reports", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const { batchNumber } = await dispatchedBatch(page, browser);

  await page.goto("/dashboard");
  await expect(page.getByText("Inspections decided")).toBeVisible();
  await expect(page.getByRole("link", { name: /^INS-/ })).toBeVisible();

  await page.goto("/analytics");
  await expect(page.getByText("Supplier scorecards")).toBeVisible();
  await expect(page.getByText("First pass yield").locator("..")).toContainText("100%");
  await expect(page.getByText("Facility pass rate")).toBeVisible();

  await page.getByLabel("From", { exact: true }).fill("2026-02-01");
  await page.getByLabel("To", { exact: true }).fill("2026-01-01");
  await expect(page.getByText("The start date is after the end date.")).toBeVisible();

  await page.goto("/reports");
  await page.getByLabel("Report", { exact: true }).selectOption("batch-traceability");
  await expect(page.getByRole("cell", { name: batchNumber })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Tata Motors" })).toBeVisible();

  const pdf = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download PDF" }).click();
  expect((await pdf).suggestedFilename()).toBe("batch-traceability-report.pdf");
  expect((await readFile(await (await pdf).path())).subarray(0, 5).toString()).toBe("%PDF-");
  const excel = page.waitForEvent("download");
  await page.getByRole("link", { name: "Download Excel" }).click();
  expect((await excel).suggestedFilename()).toBe("batch-traceability-report.xlsx");
  expect((await readFile(await (await excel).path())).subarray(0, 2).toString()).toBe("PK");
});

test("analytics fit a phone, and stay closed to other organisations", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const { facilityId } = await dispatchedBatch(page, browser);

  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto("/analytics");
  await expect(page.getByText("Inspection trend")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);

  const context = await browser.newContext();
  const stranger = await context.newPage();
  await signUp(stranger);
  await stranger.goto("/analytics");
  await expect(stranger.getByText("Inspections decided").locator("..")).toContainText("0");
  const response = await stranger.request.get(`/api/v1/analytics/overview?facilityId=${facilityId}`);
  expect(response.status()).toBe(404);
  await context.close();

  const anonymous = await browser.newPage();
  await anonymous.goto("/reports");
  await expect(anonymous).toHaveURL(/\/login/);
  await anonymous.close();
});
