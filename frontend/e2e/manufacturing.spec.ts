import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { step } from "./trace-setup";

/**
 * Manufacturing foundation, from the pages a production engineer would use: a work centre and its machine, a BOM
 * drafted, approved and exploded through a sub-assembly, and the menu following the organisation's model.
 */
test("a BOM is drafted, approved and exploded, and machines keep their status history", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const unit = (code: string) => units.find((candidate) => candidate.code === code)!.id;
  await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  await call("POST", "/materials", { code: "EN8", name: "EN8 Bar", uomId: unit("KG"), inspectionRequired: false });
  const shaft = await call("POST", "/products", { code: "SH-1", name: "Shaft", productType: "SEMI_FINISHED", uomId: unit("PCS") });
  await call("POST", "/products", { code: "GB-1", name: "Gearbox", productType: "FINISHED", uomId: unit("PCS") });

  // A discrete organisation sees BOMs, not recipes.
  await page.goto("/manufacturing/work-centers");
  const menu = page.getByRole("navigation", { name: "Main" });
  await expect(menu.getByText("Manufacturing", { exact: true })).toBeVisible();
  await expect(menu.getByRole("link", { name: "BOMs" })).toBeVisible();
  await expect(menu.getByRole("link", { name: "Recipes" })).toHaveCount(0);

  await step(page, "Add work centre", async (dialog: Locator) => {
    await dialog.getByLabel("Code").fill("CNC-1");
    await dialog.getByLabel("Name").fill("CNC cell");
    await dialog.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
  });
  await expect(page.getByRole("heading", { name: "CNC cell" })).toBeVisible();
  await step(page, "Add machine", async (dialog: Locator) => {
    await dialog.getByLabel("Code").fill("HAAS-1");
    await dialog.getByLabel("Name").fill("Haas VF-2");
  });
  await page.getByRole("link", { name: "HAAS-1 – Haas VF-2" }).click();
  await step(page, "Change status", async (dialog: Locator) => {
    await dialog.getByLabel("New status").selectOption({ label: "Breakdown" });
    await dialog.getByLabel(/Reason/).fill("Spindle seized");
  });
  await expect(page.getByText("Spindle seized").first()).toBeVisible();
  const history = page.locator("[data-slot='card']").filter({ hasText: "Status history" });
  await expect(history.getByText("breakdown")).toBeVisible();
  await expect(history.getByText("available")).toBeVisible();

  // The shaft's BOM, approved by its author because the organisation allows that.
  await page.goto("/manufacturing/settings");
  await step(page, "Change", async (dialog: Locator) => {
    await dialog.getByLabel(/Someone other than the submitter/).uncheck();
  });
  await expect(page.getByText("The submitter may approve their own")).toBeVisible();
  await call("POST", "/manufacturing/boms", { productId: shaft.id });

  await page.goto("/manufacturing/boms");
  await expect(page.getByRole("link", { name: "SH-1 – Shaft" })).toBeVisible();
  await page.getByRole("link", { name: "SH-1 – Shaft" }).click();
  await step(page, "Add line", async (dialog: Locator) => {
    await dialog.getByLabel("Material or product").selectOption({ label: "Material: EN8 – EN8 Bar" });
    await dialog.getByLabel(/^Quantity/).fill("500");
    await dialog.getByLabel("Unit").selectOption(unit("GM"));
  });
  await expect(page.getByText("1. EN8 – EN8 Bar")).toBeVisible();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await step(page, "Approve", async () => {});
  await expect(page.locator("[data-slot='badge']", { hasText: "in effect" })).toBeVisible();

  // The gearbox uses two shafts; ten gearboxes need 10 kg of bar through them.
  await page.goto("/manufacturing/boms");
  await step(page, "New BOM", async (dialog: Locator) => {
    await dialog.getByLabel("Product", { exact: true }).selectOption({ label: "GB-1 – Gearbox" });
  });
  await step(page, "Add line", async (dialog: Locator) => {
    await dialog.getByLabel("Material or product").selectOption({ label: "Product: SH-1 – Shaft" });
    await dialog.getByLabel(/^Quantity/).fill("2");
    await dialog.getByLabel("Unit").selectOption(unit("PCS"));
  });
  await page.getByLabel(/Quantity to make/).fill("10");
  await page.getByRole("button", { name: "Show" }).click();
  const needed = page.locator("[data-slot='card']").filter({ hasText: "Material needed" });
  await expect(needed.getByText("20 PCS")).toBeVisible();
  await expect(needed.getByText("10 KG").first()).toBeVisible();

  // A hybrid organisation sees recipes too.
  await page.goto("/manufacturing/settings");
  await step(page, "Change", async (dialog: Locator) => {
    await dialog.getByLabel("How your organisation manufactures").selectOption("HYBRID");
  });
  await expect(menu.getByRole("link", { name: "Recipes" })).toBeVisible();
});
