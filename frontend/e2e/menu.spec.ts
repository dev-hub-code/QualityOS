import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";

test("the menu is grouped by workflow, and is easy to open again once collapsed", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await signUp(page);
  await page.goto("/ncrs");
  const menu = page.getByRole("navigation", { name: "Main" });

  // The group of the page you are on cannot be closed, so its highlighted item stays in view.
  await expect(menu.getByRole("button", { name: "Quality" })).toBeDisabled();
  await menu.getByRole("button", { name: "Incoming material" }).click();
  await expect(menu.getByRole("link", { name: "Receipts" })).toBeHidden();
  await expect(menu.getByRole("button", { name: "Incoming material 3 hidden" })).toHaveAttribute("aria-expanded", "false");

  // Closed groups are remembered, and Open all brings everything back.
  await page.reload();
  await expect(menu.getByRole("link", { name: "Receipts" })).toBeHidden();
  await menu.getByRole("button", { name: "Open all" }).click();
  await expect(menu.getByRole("link", { name: "Receipts" })).toBeVisible();
  await expect(menu.getByRole("button", { name: "Open all" })).toHaveCount(0);

  // The << and >> button sits on the menu's right edge, half over it.
  const toggle = page.getByRole("button", { name: "Collapse menu" });
  const [edge, button] = [await page.locator("aside").boundingBox(), await toggle.boundingBox()];
  expect(Math.abs(button!.x + button!.width / 2 - (edge!.x + edge!.width))).toBeLessThan(3);

  // Collapsed to icons, the same button expands it again, and so does the logo.
  await toggle.click();
  const expand = page.getByRole("button", { name: "Expand menu", exact: true });
  await expect(expand).toBeVisible();
  await expect(menu.getByRole("link", { name: "Receipts" })).toHaveAttribute("title", "Receipts");
  await expand.click();
  await expect(page.getByRole("button", { name: "Collapse menu" })).toBeVisible();
  await page.getByRole("button", { name: "Collapse menu" }).click();
  await page.getByRole("button", { name: "ProductionOS, expand menu" }).click();
  await expect(page.getByRole("button", { name: "Collapse menu" })).toBeVisible();
});
