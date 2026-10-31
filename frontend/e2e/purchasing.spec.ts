import { expect, test, type Page } from "@playwright/test";

const PASSWORD = "correct-horse-battery";
const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function startOrganization(page: Page) {
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

test("a supplier is approved, ordered from, and delivers in two batches", async ({ page }) => {
  await startOrganization(page);

  await page.getByRole("link", { name: "Facilities" }).click();
  await page.getByRole("button", { name: "Add facility" }).click();
  await page.getByLabel("Code", { exact: true }).fill("PUNE");
  await page.getByLabel("Name", { exact: true }).fill("Pune Plant");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("link", { name: "PUNE" })).toBeVisible();

  await page.getByRole("link", { name: "Materials" }).click();
  await page.getByRole("button", { name: "Add material" }).click();
  await page.getByLabel("Code", { exact: true }).fill("EN8");
  await page.getByLabel("Name", { exact: true }).fill("EN8 Bar");
  await page.getByLabel("Unit", { exact: true }).selectOption({ label: "KG – Kilogram" });
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("link", { name: "EN8" })).toBeVisible();

  await page.getByRole("link", { name: "Suppliers" }).click();
  await page.getByRole("button", { name: "Add supplier" }).click();
  await page.getByLabel("Code", { exact: true }).fill("SUP-1");
  await page.getByLabel("Name", { exact: true }).fill("Bharat Steels");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("link", { name: "SUP-1" }).click();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByRole("button", { name: "Block" })).toBeVisible();

  await page.getByLabel("Upload a document").setInputFiles({
    name: "iso-cert.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7 certificate"),
  });
  const download = page.waitForEvent("download");
  await page.getByRole("link", { name: "iso-cert.pdf" }).click();
  expect((await download).suggestedFilename()).toBe("iso-cert.pdf");

  await page.getByRole("link", { name: "Purchase orders" }).click();
  await page.getByRole("link", { name: "New purchase order" }).click();
  await page.getByLabel("Supplier", { exact: true }).selectOption({ label: "SUP-1 – Bharat Steels" });
  await page.getByLabel("Deliver to").selectOption({ label: "PUNE – Pune Plant" });
  await page.getByLabel("Material", { exact: true }).selectOption({ label: "EN8 – EN8 Bar" });
  await page.getByLabel("Quantity", { exact: true }).fill("100");
  await page.getByRole("button", { name: "Save draft" }).click();
  await page.getByRole("button", { name: "Issue" }).click();

  await page.getByRole("link", { name: "Receive material" }).click();
  await page.getByRole("button", { name: "Add batch" }).click();
  await page.getByLabel("Supplier batch").nth(0).fill("HEAT-1");
  await page.getByLabel("Quantity received").nth(0).fill("90");
  await page.getByLabel("Supplier batch").nth(1).fill("HEAT-2");
  await page.getByLabel("Quantity received").nth(1).fill("20");
  await page.getByRole("button", { name: "Record receipt" }).click();
  await expect(page.getByText("Line 1: only 100 remains to be received.")).toBeVisible();

  await page.getByLabel("Quantity received").nth(1).fill("10");
  await page.getByRole("button", { name: "Record receipt" }).click();
  await expect(page).toHaveURL(/material-receipts\//);
  await expect(page.getByText("HEAT-2")).toBeVisible();
  await expect(page.getByText("pending").first()).toBeVisible();

  await page.getByRole("link", { name: "View order" }).click();
  await expect(page.getByText("received", { exact: true }).first()).toBeVisible();
});
