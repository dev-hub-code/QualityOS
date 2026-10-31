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
