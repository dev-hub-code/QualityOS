import { expect, test, type Page } from "@playwright/test";
import { acceptInvitation, inviteUser } from "./mail";

const PASSWORD = "correct-horse-battery";
const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function dialogAdd(page: Page, button: string, fill: (page: Page) => Promise<void>) {
  await page.getByRole("button", { name: button }).click();
  await fill(page);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}

async function signUp(page: Page) {
  await page.goto("/register");
  await page.getByLabel("Organisation name").fill(`Acme ${unique()}`);
  await page.getByLabel("Your name").fill("Asha Rao");
  await page.getByLabel("Work email").fill(`owner-${unique()}@example.com`);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create organisation" }).click();
  await expect(page).toHaveURL(/onboarding/);
  await page.getByLabel("GSTIN").fill("27ABCDE1234F1Z5");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/dashboard/);
}

/** Facility, material, approved supplier, issued order and received batch, through the API proxy. */
async function receiveMaterial(page: Page) {
  const call = (method: string, path: string, data: unknown = {}) =>
    page.evaluate(
      async ([method, path, data]) => {
        const init =
          method === "GET"
            ? {}
            : { method, headers: { "content-type": "application/json" }, body: JSON.stringify(data) };
        const response = await fetch(`/api/v1${path}`, init);
        return (await response.json()).data;
      },
      [method, path, data] as const,
    );
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", {
    code: "EN8",
    name: "EN8 Bar",
    uomId: units.find((unit) => unit.code === "PCS")?.id,
    inspectionRequired: true,
  });
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const order = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: new Date().toISOString().slice(0, 10),
    items: [{ materialId: material.id, quantity: "100" }],
  });
  await call("POST", `/purchase-orders/${order.id}/issue`);
  await call("POST", "/material-receipts", {
    purchaseOrderId: order.id,
    receivedOn: new Date().toISOString().slice(0, 10),
    items: [{ purchaseOrderItemId: order.items[0].id, supplierBatch: "HEAT-1", quantity: "100" }],
  });
}

test("a template is published, and a failed critical measurement never releases material", async ({
  page,
  browser,
}) => {
  await signUp(page);
  await receiveMaterial(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);

  await page.goto("/inspection-templates");
  await dialogAdd(page, "Add template", async (dialog) => {
    await dialog.getByLabel("Code", { exact: true }).fill("BAR-IQC");
    await dialog.getByLabel("Name", { exact: true }).fill("Bar incoming");
    await dialog.getByLabel("Type").selectOption("IQC");
  });
  await expect(page).toHaveURL(/inspection-templates\//);

  await page.getByLabel("Sample size").fill("2");
  await page.getByRole("button", { name: "Add parameter" }).click();
  await page.getByLabel("Parameter code").fill("DIA");
  await page.getByLabel("Parameter name").fill("Diameter");
  await page.getByLabel("Minimum").fill("19.9");
  await page.getByLabel("Maximum").fill("20.1");
  await page.getByLabel("Criticality").selectOption("CRITICAL");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.getByRole("button", { name: "Publish" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "New version" })).toBeVisible();
  await expect(page.getByText("19.9 to 20.1")).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/inspections");
  await page.getByRole("button", { name: "Inspect" }).click();
  await page.getByLabel("Template").selectOption({ label: "BAR-IQC – Bar incoming" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);

  await page.getByLabel("DIA sample 1").fill("20.0");
  await page.getByLabel("DIA sample 2").fill("25");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(page.getByText("submitted", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("hold", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Someone other than the inspector")).toBeVisible();
  const url = page.url();

  await manager.goto("/notifications");
  await manager.getByRole("button", { name: /Critical failure/ }).click();
  await expect(manager).toHaveURL(url);
  await expect(manager.getByRole("link", { name: "Notifications", exact: true }).first()).toBeAttached();
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await manager.getByRole("button", { name: "Record disposition" }).click();

  const disposition = manager.getByRole("dialog").getByLabel("Disposition", { exact: true });
  await expect(disposition.locator("option", { hasText: "Reject" })).toBeAttached();
  expect(await disposition.locator("option").allTextContents()).not.toContain("Accept");
  await disposition.selectOption("REJECT");
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(manager.getByText("completed", { exact: true }).first()).toBeVisible();

  await manager.getByRole("link", { name: "Report" }).click();
  await expect(manager.getByRole("heading", { name: /Inspection report/ })).toBeVisible();
  await expect(manager.getByText("19.9 to 20.1").first()).toBeVisible();
});
