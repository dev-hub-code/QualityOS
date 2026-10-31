import { expect, test, type Page } from "@playwright/test";
import { apiCaller, signUp, unique } from "./helpers";
import { dispatchedBatch } from "./trace-setup";

async function searchFor(page: Page, term: string) {
  await page.getByLabel("Search", { exact: true }).fill(term);
  await page.getByRole("button", { name: "Search", exact: true }).click();
}

test("a serial number is found directly and its detail page shows where it went", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const serial = `SN-${unique()}`;
  const { batchNumber } = await dispatchedBatch(page, browser, [serial, `${serial}-B`]);

  await page.goto("/traceability/serials");
  await expect(page.getByRole("heading", { name: "Serial number search" })).toBeVisible();
  await expect(page.getByText("Enter a serial number or part of one")).toBeVisible();

  await searchFor(page, serial.toLowerCase());
  await expect(page.getByText("2 found")).toBeVisible();
  const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: serial, exact: true }) });
  await expect(row).toContainText("SHAFT – Drive Shaft");
  await expect(row).toContainText(batchNumber);
  await expect(row).toContainText("pass");
  await expect(row).toContainText("released");
  await expect(row).toContainText("awaiting shipment");
  await expect(row).toContainText("Tata Motors");

  await row.getByRole("link", { name: serial, exact: true }).click();
  await expect(page.getByRole("heading", { name: serial })).toBeVisible();
  await expect(page.getByText("SHAFT – Drive Shaft").first()).toBeVisible();
  await expect(page.getByRole("link", { name: batchNumber })).toBeVisible();
  await expect(page.getByRole("link", { name: /^INS-/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /^DSP-/ })).toBeVisible();
  await expect(page.getByText(/Tata Motors on/)).toBeVisible();

  await page.getByRole("link", { name: "Back to search" }).click();
  await expect(page.getByText("2 found")).toBeVisible();
  await expect(page.getByLabel("Search", { exact: true })).toHaveValue(serial.toLowerCase());

  await searchFor(page, "NOTHING-LIKE-THIS");
  await expect(page.getByText("No serial numbers match.")).toBeVisible();
});

test("global and traceability search lead to the unit", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const serial = `SN-${unique()}`;
  await dispatchedBatch(page, browser, [serial]);

  await page.goto("/dashboard");
  await page.getByLabel("Search everything").fill(serial);
  await page.getByLabel("Search everything").press("Enter");
  await page.getByRole("link", { name: serial }).click();
  await expect(page.getByRole("heading", { name: serial })).toBeVisible();

  await page.goto("/traceability");
  await page.getByLabel(/Batch, serial/).fill(serial);
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByRole("link", { name: serial }).click();
  await expect(page.getByRole("heading", { name: serial })).toBeVisible();
});

test("the search shows loading and error states, and fits a phone", async ({ page, browser }) => {
  test.setTimeout(90_000);
  const serial = `SN-${unique()}`;
  await dispatchedBatch(page, browser, [serial]);
  await page.goto("/traceability/serials");

  await page.route("**/api/v1/traceability/serials?*", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 600));
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ success: false, error: { code: "INTERNAL_ERROR", message: "Search is unavailable." } }),
    });
  });
  await searchFor(page, serial);
  await expect(page.getByRole("status")).toHaveText("Searching…");
  await expect(page.getByText("Search is unavailable.")).toBeVisible();
  await page.unroute("**/api/v1/traceability/serials?*");

  await page.setViewportSize({ width: 390, height: 800 });
  await page.reload();
  await expect(page.getByText("1 found")).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.getByRole("link", { name: serial }).click();
  await expect(page.getByRole("heading", { name: serial })).toBeVisible();
  const detailOverflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(detailOverflow).toBeLessThanOrEqual(0);
});

test("serial pages stay closed to anyone who should not see them", async ({ page, browser }) => {
  test.setTimeout(120_000);
  const serial = `SN-${unique()}`;
  const { manager } = await dispatchedBatch(page, browser, [serial]);
  const detail = `/traceability/serials/${serial}`;

  const anonymous = await browser.newPage();
  await anonymous.goto(detail);
  await expect(anonymous).toHaveURL(/\/login/);
  await anonymous.close();

  const stranger = await browser.newContext();
  const strangerPage = await stranger.newPage();
  await signUp(strangerPage);
  await strangerPage.goto(detail);
  await expect(strangerPage.getByText("This record does not exist.")).toBeVisible();
  await strangerPage.goto(`/traceability/serials?q=${serial}`);
  await expect(strangerPage.getByText("No serial numbers match.")).toBeVisible();
  await stranger.close();

  await manager.goto(detail);
  await expect(manager.getByRole("heading", { name: serial })).toBeVisible();

  const owner = apiCaller(page);
  const other = await owner("POST", "/facilities", { code: "NASIK", name: "Nashik Plant" });
  const members: { items: { fullName: string; membershipId: string }[] } = await owner("GET", "/users");
  const member = members.items.find((item) => item.fullName === "Meera Iyer");
  await owner("PUT", `/users/${member?.membershipId}/facilities`, { facilityIds: [other.id] });

  await manager.goto(detail);
  await expect(manager.getByText("This record does not exist.")).toBeVisible();
  await manager.goto(`/traceability/serials?q=${serial}`);
  await expect(manager.getByText("No serial numbers match.")).toBeVisible();
});
