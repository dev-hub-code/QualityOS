import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";

test("a visitor reads the page, sees live prices and reaches sign-up", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("what failed");
  const pricing = page.locator("#pricing");
  await expect(pricing.getByRole("heading", { name: "Starter" })).toBeVisible();
  await expect(pricing.getByText("Custom", { exact: true })).toBeVisible();

  await pricing.getByRole("link", { name: /-day trial/ }).click();
  await expect(page).toHaveURL(/\/register/);
});

test("someone signed in goes straight to their dashboard", async ({ page }) => {
  await signUp(page);
  await page.goto("/");
  await expect(page).toHaveURL(/\/dashboard/);
});

test("the page fits a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});

test("a visitor reaches the documentation and every topic has an example", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Docs" }).click();
  await expect(page).toHaveURL(/\/docs$/);
  await expect(page.getByRole("heading", { level: 1, name: "Documentation" })).toBeVisible();
  const links = page.locator('main a[href^="/docs/"]');
  const topics = await links.evaluateAll((items) => items.map((item) => item.getAttribute("href")!));
  expect(topics.length).toBeGreaterThan(20);
  for (const topic of topics) {
    await page.goto(topic);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByText("Worked example")).toBeVisible();
    await expect(page.getByRole("figure")).toBeVisible();
  }
});

test("the documentation fits a phone", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  for (const path of ["/docs", "/docs/inspections", "/docs/recalls", "/docs/reports"]) {
    await page.goto(path);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
  }
  await page.getByText("All topics").click();
  await page.getByRole("navigation", { name: "Topics, compact" }).getByRole("link", { name: "Calibration" }).click();
  await expect(page).toHaveURL(/\/docs\/calibration/);
});
