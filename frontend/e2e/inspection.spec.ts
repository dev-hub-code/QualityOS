import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

async function dialogAdd(page: Page, button: string, fill: (page: Page) => Promise<void>) {
  await page.getByRole("button", { name: button }).click();
  await fill(page);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}

/** Facility, material, approved supplier, issued order and received batch, through the API proxy. */
async function receiveMaterial(page: Page) {
  const call = apiCaller(page);
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
  test.setTimeout(90_000);
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

  // The failure now has an NCR, which runs through a verified CAPA to closure.
  await manager.goto(url);
  await manager.getByRole("link", { name: /^NCR-/ }).click();
  await expect(manager).toHaveURL(/ncrs\//);
  const ncrUrl = manager.url();

  const step = async (who: Page, button: string, fill?: (dialog: Locator) => Promise<void>) => {
    await who.getByRole("button", { name: button, exact: true }).click();
    const dialog = who.getByRole("dialog");
    await fill?.(dialog);
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toBeHidden();
  };

  await manager.goto("/defect-categories");
  await step(manager, "Add category", async (dialog) => {
    await dialog.getByLabel("Code").fill("DIM");
    await dialog.getByLabel("Name").fill("Dimensional");
  });
  await expect(manager.getByText("DIM – Dimensional")).toBeVisible();

  await manager.goto(ncrUrl);
  await step(manager, "Assign", (dialog) => dialog.getByLabel("Assign to").selectOption({ label: "Asha Rao" }).then(() => {}));
  await step(manager, "Record containment", (dialog) => dialog.getByLabel("Containment action").fill("Lot quarantined"));
  await step(manager, "Record root cause", async (dialog) => {
    await dialog.getByLabel("Root cause").fill("Heat treatment drift");
    await dialog.getByLabel("Defect category").selectOption({ label: "DIM – Dimensional" });
  });

  await manager.getByRole("button", { name: "Request verification" }).click();
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(manager.getByRole("dialog").getByText("needs a closed CAPA")).toBeVisible();
  await manager.goto(ncrUrl);

  await manager.getByRole("button", { name: "Raise CAPA" }).click();
  await manager.getByRole("dialog").getByLabel("Owner").selectOption({ label: "Asha Rao" });
  await manager.getByRole("dialog").getByLabel("Target date").fill("2036-01-15");
  await manager.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(manager).toHaveURL(/capas\//);
  const capaUrl = manager.url();

  await page.goto(capaUrl);
  await page.getByRole("button", { name: "Start analysis" }).click();
  await page.getByLabel("Why 1").fill("Diameter oversize");
  await page.getByLabel("Why 2").fill("Furnace drift");
  await page.getByLabel("Machine").fill("Thermocouple aged");
  await page.getByLabel("Root cause").fill("No thermocouple replacement schedule");
  await page.getByRole("button", { name: "Finish analysis" }).click();
  await step(page, "Add action", async (dialog) => {
    await dialog.getByLabel("Type").selectOption("CORRECTIVE");
    await dialog.getByLabel("Description").fill("Replace thermocouple");
    await dialog.getByLabel("Owner").selectOption({ label: "Asha Rao" });
    await dialog.getByLabel("Target date").fill("2036-01-10");
  });
  await page.getByRole("button", { name: "Start implementation" }).click();
  await step(page, "Complete", (dialog) => dialog.getByLabel("What was done").fill("Replaced and calibrated"));
  await page.getByRole("button", { name: "Submit for verification" }).click();
  await expect(page.getByText("Someone other than the CAPA owner")).toBeVisible();

  await manager.goto(capaUrl);
  await step(manager, "Verify effectiveness", async (dialog) => {
    await dialog.getByLabel("Result").selectOption("true");
    await dialog.getByLabel("How it was checked").fill("Next three lots measured");
    await dialog.getByLabel("Findings").fill("All within limits");
  });
  await step(manager, "Close CAPA", (dialog) => dialog.getByLabel("Closure note").fill("Effective"));
  await expect(manager.getByText("closed", { exact: true }).first()).toBeVisible();

  await page.goto(ncrUrl);
  await step(page, "Request verification");
  await manager.goto(ncrUrl);
  await step(manager, "Close NCR", (dialog) => dialog.getByLabel("Closure note").fill("Verified on the floor"));
  await expect(manager.getByText("closed", { exact: true }).first()).toBeVisible();
  await expect(manager.getByRole("button", { name: "Raise CAPA" })).toHaveCount(0);
});
