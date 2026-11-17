import { expect, test } from "@playwright/test";
import { apiCaller, signUp, unique } from "./helpers";
import { makePlatformAdmin, signInOperator } from "./operators";

test("support reads a customer's records only while the customer allows it, and the customer sees what it read", async ({ page, browser }) => {
  test.setTimeout(90_000);
  await signUp(page);
  const call = apiCaller(page);
  const ncr = await call("POST", "/ncrs", { title: "Cracked weld", description: "Found at final check", severity: "MAJOR" });
  const { tenantId } = await call("GET", "/auth/me");

  const context = await browser.newContext();
  const operator = await context.newPage();
  const operatorEmail = `support-${unique()}@example.com`;
  await signUp(operator, operatorEmail);
  makePlatformAdmin(operatorEmail);
  await signInOperator(operator, operatorEmail);

  // Nothing to open until the customer allows it.
  await operator.goto(`/platform/organisations/${tenantId}`);
  await expect(operator.getByText(/Support access not granted/)).toBeVisible();
  await expect(operator.getByRole("button", { name: "Open support view" })).toHaveCount(0);

  await page.goto("/settings");
  await expect(page.getByText("Support has no access.")).toBeVisible();
  await page.getByLabel("Allow access for").selectOption({ label: "24 hours" });
  await page.getByLabel("Note for support (optional)").fill("Ticket 42");
  await page.getByRole("button", { name: "Allow support access" }).click();
  await expect(page.getByText(/Support can read your records until/)).toBeVisible();

  await operator.reload();
  await operator.getByRole("button", { name: "Open support view" }).click();
  await operator.getByLabel(/Why you need to look/).fill("Checking NCR numbering");
  await operator.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(operator).toHaveURL(/\/dashboard$/);
  await expect(operator.getByRole("status").filter({ hasText: "Support view of" })).toBeVisible();

  await operator.goto(`/ncrs/${ncr.id}`);
  await expect(operator.getByRole("heading", { name: /Cracked weld|NCR-/ }).first()).toBeVisible();
  // Read-only, whatever the screen offers.
  const refused = await operator.evaluate(async () => {
    const response = await fetch("/api/v1/ncrs", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ title: "X", description: "Y", severity: "MINOR" }),
    });
    return { status: response.status, message: (await response.json()).error?.message };
  });
  expect(refused).toEqual({ status: 403, message: "Support access is read-only." });

  await operator.getByRole("button", { name: "End support session" }).click();
  await expect(operator).toHaveURL(/\/platform\/dashboard/);

  await page.reload();
  await expect(page.getByText(/opened a support session/)).toBeVisible();
  await expect(page.getByText("Checking NCR numbering")).toBeVisible();
  await expect(page.getByText(`/ncrs/${ncr.id}`, { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Withdraw access" }).click();
  await expect(page.getByText("Support has no access.")).toBeVisible();
  await operator.goto(`/platform/organisations/${tenantId}`);
  await expect(operator.getByText(/Support access not granted/)).toBeVisible();
  await context.close();
});
