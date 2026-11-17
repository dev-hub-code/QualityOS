import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";

test("an administrator sets the organisation's review and NCR rules", async ({ page }) => {
  await signUp(page);
  await page.goto("/settings");

  const capaRule = page.getByLabel("A closed CAPA is needed before verification for");
  await expect(capaRule).toHaveValue("MAJOR");
  await capaRule.selectOption({ label: "Critical NCRs only" });
  await expect(capaRule).toBeEnabled();

  const verificationRule = page.getByLabel("A CAPA's fix must be verified as effective for");
  await verificationRule.selectOption({ label: "Every NCR" });
  await expect(verificationRule).toBeEnabled();

  await page.getByRole("checkbox", { name: "Let me review my own work while I am the only member" }).click();
  await expect(page.getByRole("checkbox", { name: "Let me review my own work while I am the only member" })).toBeChecked();

  await page.reload();
  await expect(capaRule).toHaveValue("CRITICAL");
  await expect(verificationRule).toHaveValue("MINOR");
  await expect(page.getByRole("checkbox", { name: "Let me review my own work while I am the only member" })).toBeChecked();
});

// A 1×1 transparent PNG.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
  "base64",
);

test("an administrator uploads the organisation's logo, which the menu shows", async ({ page }) => {
  await signUp(page);
  await page.goto("/settings");
  await expect(page.getByText("No logo yet.")).toBeVisible();

  await page.getByLabel("Logo file").setInputFiles({ name: "notes.png", mimeType: "image/png", buffer: Buffer.from("not an image") });
  await expect(page.getByText("The logo must be a PNG or JPEG image.")).toBeVisible();

  await page.getByLabel("Logo file").setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByRole("img", { name: "Organisation logo" })).toBeVisible();
  await expect(page.locator("aside").getByRole("img", { name: / logo$/ })).toBeVisible();

  await page.reload();
  await expect(page.getByRole("button", { name: "Replace logo" })).toBeVisible();
  await page.getByRole("button", { name: "Remove logo" }).click();
  await expect(page.getByText("No logo yet.")).toBeVisible();
  await expect(page.locator("aside").getByRole("img", { name: / logo$/ })).toHaveCount(0);
});
