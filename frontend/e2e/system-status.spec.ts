import { expect, test } from "@playwright/test";

test("home page reports a healthy stack", async ({ page }) => {
  await page.goto("/status");

  await expect(page.getByRole("heading", { name: "QualityOS" })).toBeVisible();
  await expect(page.getByText("All systems operational")).toBeVisible();
});

test("frontend exposes a health endpoint", async ({ request }) => {
  const response = await request.get("/api/health");

  expect(response.ok()).toBeTruthy();
  expect(await response.json()).toEqual({ status: "UP" });
});
