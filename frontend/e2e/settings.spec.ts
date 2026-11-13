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
