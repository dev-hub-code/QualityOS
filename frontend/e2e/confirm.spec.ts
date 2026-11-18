import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";

test("an important action asks first, and does nothing unless confirmed", async ({ page }) => {
  await signUp(page);
  await page.goto("/categories");
  const suppliers = page.getByRole("region", { name: "Supplier categories" });
  await page.getByRole("button", { name: "Add supplier category" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill("Forgings");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(suppliers.getByText("Forgings")).toBeVisible();

  // The question names the record and says what happens; focus starts on Cancel, so Enter does not confirm.
  await suppliers.getByRole("button", { name: "Delete" }).click();
  const question = page.getByRole("alertdialog");
  await expect(question).toContainText("Delete Forgings?");
  await expect(question).toContainText("This cannot be undone.");
  await expect(question.getByRole("button", { name: "Cancel" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(question).toBeHidden();
  await expect(suppliers.getByText("Forgings")).toBeVisible();

  // Escape is no as well.
  await suppliers.getByRole("button", { name: "Delete" }).click();
  await page.keyboard.press("Escape");
  await expect(question).toBeHidden();
  await page.reload();
  await expect(suppliers.getByText("Forgings")).toBeVisible();

  // Only the confirm button goes ahead.
  await suppliers.getByRole("button", { name: "Delete" }).click();
  await question.getByRole("button", { name: "Delete" }).click();
  await expect(question).toBeHidden();
  await expect(suppliers.getByText("Forgings")).toHaveCount(0);
});
