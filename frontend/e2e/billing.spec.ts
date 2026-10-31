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
  await page.getByLabel("Reason").fill("Price review");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("cell", { name: "₹3,111.00" }).first()).toBeVisible();
  await page.getByRole("button", { name: "Edit Starter" }).click();
  await page.getByLabel("Monthly price (blank for custom)").fill("2999");
  await page.getByLabel("Reason").fill("Back to the list price");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("cell", { name: "₹2,999.00" }).first()).toBeVisible();
});

test("the platform console is closed to everyone else", async ({ page }) => {
  await signUp(page);
  await expect(page.getByRole("link", { name: "Control Center" })).toHaveCount(0);
  for (const path of ["plans", "dashboard", "organisations", "organisations/export"]) {
    expect((await page.request.get(`/api/v1/platform/${path}`)).status()).toBe(403);
  }
  await page.goto("/platform/dashboard");
  await expect(page.getByText("You do not have access to the Control Center.")).toBeVisible();
});

test("a platform admin runs a plan and finds a customer from the Control Center", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const email = `admin-${unique()}@example.com`;
  await signUp(page, email);
  makePlatformAdmin(email);
  const context = await browser.newContext();
  const customerPage = await context.newPage();
  const customer = `customer-${unique()}@example.com`;
  await signUp(customerPage, customer);
  await context.close();

  await page.goto("/dashboard");
  await page.getByRole("link", { name: "Control Center" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByText("MRR", { exact: true })).toBeVisible();
  await expect(page.getByText("Subscriptions by plan")).toBeVisible();
  await page.getByLabel("Period").selectOption("LAST_7");
  await expect(page.getByRole("navigation", { name: "Control Center" })).toBeVisible();

  const code = `E2E_${unique().replaceAll("-", "_").toUpperCase()}`.slice(0, 28);
  await page.getByRole("link", { name: "Plans" }).click();
  await page.getByRole("button", { name: "New plan" }).click();
  await page.getByLabel("Code, such as GROWTH_PLUS").fill(code);
  await page.getByLabel("Name").fill(code);
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("button", { name: `Activate ${code}` }).click();
  await page.getByLabel("Reason").fill("Launch");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("button", { name: `Archive ${code}` }).click();
  await page.getByLabel("Reason").fill("Launched by mistake");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("link", { name: code }).click();
  await expect(page.getByText("Launched by mistake")).toBeVisible();

  await page.getByRole("link", { name: "Organisations" }).click();
  await page.getByLabel("Search name, email or GSTIN").fill(customer);
  await expect(page.getByRole("status")).toHaveText("1 organisation, page 1");
  await page.getByRole("row").nth(1).getByRole("link").click();
  await expect(page.getByText(customer)).toBeVisible();
  await expect(page.getByText("Figures only.")).toBeVisible();
});

test("billing fits a phone", async ({ page }) => {
  await signUp(page);
  await page.setViewportSize({ width: 390, height: 800 });
  await page.goto("/settings/billing");
  await expect(page.getByText("Choose Growth")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});

test("a platform admin suspends a customer, brings them back, and reconciles a payment", async ({ page, browser }) => {
  test.setTimeout(60_000);
  const ownerEmail = `billing-${unique()}@example.com`;
  await signUp(page, ownerEmail);
  const me: { organizations: { name: string }[] } = await apiCaller(page)("GET", "/auth/me");
  const organization = me.organizations[0].name;

  const context = await browser.newContext();
  const admin = await context.newPage();
  const adminEmail = `admin-${unique()}@example.com`;
  await signUp(admin, adminEmail);
  makePlatformAdmin(adminEmail);

  await admin.goto("/platform/subscriptions");
  await admin.getByLabel("Search").fill(ownerEmail);
  await admin.getByRole("link", { name: organization }).click();

  await admin.getByRole("button", { name: "Suspend" }).click();
  await admin.getByLabel("Reason").fill("Awaiting confirmation of the account holder");
  await admin.getByRole("button", { name: "Save" }).click();
  await expect(admin.getByText(/Suspended on .*Awaiting confirmation of the account holder/)).toBeVisible();
  await page.reload();
  await expect(page).toHaveURL(/login/);

  await admin.getByRole("button", { name: "Reactivate" }).click();
  await admin.getByLabel("Reason").fill("Confirmed by phone");
  await admin.getByRole("button", { name: "Save" }).click();
  await expect(admin.getByRole("button", { name: "Suspend" })).toBeVisible();

  await admin.goto("/platform/organisations");
  await admin.getByLabel("Search name, email or GSTIN").fill(ownerEmail);
  await admin.getByRole("row", { name: organization }).getByRole("button", { name: "Raise invoice" }).click();
  await admin.getByRole("dialog").getByLabel("Plan").selectOption({ label: "Growth" });
  await admin.getByLabel("Billing period").selectOption("MONTHLY");
  await admin.getByLabel("Reason").fill("Moving from trial");
  await admin.getByRole("button", { name: "Save" }).click();

  await admin.goto("/platform/invoices");
  await admin.getByLabel("Invoice or organisation").fill(organization);
  const row = admin.getByRole("row", { name: organization });
  await row.getByRole("button", { name: "View" }).click();
  await expect(admin.getByText("Taxable value")).toBeVisible();
  await admin.keyboard.press("Escape");
  await row.getByRole("button", { name: "Record payment" }).click();
  const utr = `UTR-${Date.now()}`;
  await admin.getByLabel("Bank or UTR reference").fill(utr);
  await admin.getByLabel("Payment method").selectOption("UPI");
  await admin.getByRole("button", { name: "Save" }).click();
  await expect(row.getByRole("cell", { name: "paid", exact: true })).toBeVisible();

  await admin.goto("/platform/payments");
  await admin.getByLabel("UTR, invoice or organisation").fill(utr);
  await expect(admin.getByRole("row", { name: organization })).toContainText(adminEmail);
  await context.close();
});

test("a platform admin defines a feature, puts it on a plan, and reads usage and analytics", async ({ page }) => {
  test.setTimeout(60_000);
  const email = `admin-${unique()}@example.com`;
  await signUp(page, email);
  makePlatformAdmin(email);
  const code = `E2E_${Date.now()}`;

  await page.goto("/platform/features");
  await expect(page.getByRole("row", { name: /Dashboards and reports/ }).first()).toContainText("Enforced");
  await page.getByRole("button", { name: "New feature" }).click();
  await page.getByLabel("Code, such as PRIORITY_SUPPORT").fill(code);
  await page.getByLabel("Name").fill(`Support ${code}`);
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("row", { name: code }).first()).toContainText("Listed only, not enforced");

  await page.getByRole("button", { name: `Support ${code} on Starter: not included` }).click();
  await page.getByLabel(/Reason/).fill("Testing the catalogue");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: `Support ${code} on Starter: included` })).toBeVisible();

  await page.getByRole("button", { name: `Support ${code} on Starter: included` }).click();
  await page.getByLabel(/Reason/).fill("Finished testing");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: `Support ${code} on Starter: not included` })).toBeVisible();
  await page.getByRole("row", { name: code }).first().getByRole("button", { name: "Deactivate" }).click();
  await page.getByLabel("Reason").fill("Finished testing");
  await page.getByRole("button", { name: "Save" }).click();

  await page.goto("/platform/usage");
  await expect(page.getByRole("status")).toContainText("page 1");
  await page.getByRole("button", { name: "History" }).first().click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");

  await page.goto("/platform/analytics");
  await expect(page.getByText("Revenue churn")).toBeVisible();
  const csv = page.waitForEvent("download");
  await page.getByRole("link", { name: "Export CSV" }).click();
  expect((await readFile(await (await csv).path())).toString()).toContain("MRR (INR)");
});

