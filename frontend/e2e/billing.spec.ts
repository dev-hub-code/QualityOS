import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { expect, test } from "@playwright/test";
import { apiCaller, signUp, unique } from "./helpers";

/** Being a platform administrator can only be granted in the database, on purpose. */
function makePlatformAdmin(email: string) {
  execFileSync("docker", [
    "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "qualityos", "-c",
    `INSERT INTO platform_admins (user_id) SELECT id FROM users WHERE email = '${email}'`,
  ]);
}

test("an owner chooses a plan, a platform admin records the payment, and the plan starts", async ({ page, browser }) => {
  test.setTimeout(60_000);
  await signUp(page);

  const me: { organizations: { name: string }[] } = await apiCaller(page)("GET", "/auth/me");
  const organization = me.organizations[0].name;

  await page.goto("/settings/billing");
  await expect(page.getByText("Professional plan")).toBeVisible();
  await expect(page.getByText("Custom", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Choose Growth" }).click();
  await expect(page.getByText(/The Growth plan starts\s+once it is paid/)).toBeVisible();

  const pdf = page.waitForEvent("download");
  await page.getByRole("link", { name: "PDF" }).click();
  expect((await readFile(await (await pdf).path())).subarray(0, 5).toString()).toBe("%PDF-");

  const context = await browser.newContext();
  const admin = await context.newPage();
  const adminEmail = `admin-${unique()}@example.com`;
  await signUp(admin, adminEmail);
  makePlatformAdmin(adminEmail);
  await admin.goto("/platform/organisations");
  await admin.getByRole("row", { name: organization }).getByRole("button", { name: "Record ₹7,078.82" }).click();
  await admin.getByLabel("Bank or UTR reference").fill(`UTR-${Date.now()}`);
  await admin.getByRole("button", { name: "Save" }).click();
  await context.close();

  await page.reload();
  await expect(page.getByText("Growth plan")).toBeVisible();
  await expect(page.getByText("Payment history")).toBeVisible();
  await expect(page.getByText(/UTR-/)).toBeVisible();
});

test("a platform admin changes a price without a release", async ({ page }) => {
  const email = `admin-${unique()}@example.com`;
  await signUp(page, email);
  makePlatformAdmin(email);
  await page.goto("/platform/plans");
  await page.getByRole("button", { name: "Edit Starter" }).click();
  await page.getByLabel("Monthly price (blank for custom)").fill("3111");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("₹3,111.00")).toBeVisible();
  await page.getByRole("button", { name: "Edit Starter" }).click();
  await page.getByLabel("Monthly price (blank for custom)").fill("2999");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByText("₹2,999.00")).toBeVisible();
});

test("the platform console is closed to everyone else", async ({ page }) => {
  await signUp(page);
  await expect(page.getByRole("link", { name: "Plans" })).toHaveCount(0);
  expect((await page.request.get("/api/v1/platform/plans")).status()).toBe(403);
});

test("billing fits a phone", async ({ page }) => {
  await signUp(page);
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto("/settings/billing");
  await expect(page.getByText("Choose Growth")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});
