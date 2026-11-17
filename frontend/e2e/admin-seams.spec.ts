import { expect, test } from "@playwright/test";
import { apiCaller, signUp, unique } from "./helpers";

test("an owner reads the organisation's audit log and narrows it", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const code = `AUD-${unique()}`.slice(0, 20);
  await call("POST", "/facilities", { code, name: "Audit Plant" });

  await page.getByRole("link", { name: "Audit log" }).click();
  await expect(page.getByRole("heading", { name: "Audit log" })).toBeVisible();
  await page.getByRole("textbox", { name: "Action" }).fill("FACILITY_CREATED");
  const row = page.getByRole("row").filter({ hasText: "FACILITY_CREATED" });
  await expect(row).toHaveCount(1);
  await row.getByRole("button", { name: "View" }).click();
  await expect(page.getByRole("dialog").getByText("Facility", { exact: false }).first()).toBeVisible();
});

test("search finds machines and work orders alongside quality records", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const facility = await call("POST", "/facilities", { code: `S-${unique()}`.slice(0, 20), name: "Search Plant" });
  const centre = await call("POST", "/manufacturing/work-centers", { code: "WC-S", name: "Turning", facilityId: facility.id });
  await call("POST", "/manufacturing/machines", { code: "LATHE-77", name: "Search Lathe", workCenterId: centre.id });

  await page.goto("/dashboard");
  await page.getByLabel("Search everything").fill("lathe-77");
  await page.getByLabel("Search everything").press("Enter");
  const machines = page.locator("[data-slot='card']").filter({ hasText: "Machines" });
  await expect(machines.getByRole("link", { name: "LATHE-77" })).toBeVisible();
  await expect(machines.getByText("Search Lathe")).toBeVisible();
  await expect(page.locator("[data-slot='card']").filter({ has: page.getByText("Work orders", { exact: true }) })).toContainText("Nothing matches.");
  await machines.getByRole("link", { name: "LATHE-77" }).click();
  await expect(page).toHaveURL(/\/manufacturing\/machines\/[0-9a-f-]+$/);
});

test("search finds instruments, products and customers", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const facility = await call("POST", "/facilities", { code: `I-${unique()}`.slice(0, 20), name: "Gauge Plant" });
  await call("POST", "/instruments", {
    instrumentCode: "GAUGE-42", name: "Bore gauge", category: "Dimensional", facilityId: facility.id,
    frequencyDays: 180, nextDueOn: new Date().toISOString().slice(0, 10),
  });
  await call("POST", "/customers", { code: "GAUGE-CUST", name: "Gauge Buyers" });

  await page.goto("/dashboard");
  await page.getByLabel("Search everything").fill("gauge");
  await page.getByLabel("Search everything").press("Enter");
  const card = (title: string) =>
    page.locator("[data-slot='card']").filter({ has: page.getByText(title, { exact: true }) });
  await expect(card("Instruments").getByRole("link", { name: "GAUGE-42" })).toBeVisible();
  await expect(card("Customers").getByText("Gauge Buyers")).toBeVisible();
  await expect(card("Products")).toContainText("Nothing matches.");
  await card("Instruments").getByRole("link", { name: "GAUGE-42" }).click();
  await expect(page).toHaveURL(/\/calibration\/[0-9a-f-]+$/);
});
