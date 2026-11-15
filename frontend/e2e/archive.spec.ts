import { expect, test } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";

/** An archived record is shown on request and brought back from the list. */
test("an archived product can be found and restored", async ({ page }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const pcs = units.find((unit) => unit.code === "PCS")!.id;
  const product = await call("POST", "/products", { code: "OLD-1", name: "Retired Shaft", productType: "FINISHED", uomId: pcs });
  const archived = await page.evaluate(
    async (id) => (await fetch(`/api/v1/products/${id}`, { method: "DELETE" })).status,
    product.id as string,
  );
  expect(archived).toBe(204);

  await page.goto("/products");
  await expect(page.getByText("No products yet.")).toBeVisible();
  await page.getByLabel("Show archived").check();
  const row = page.getByRole("row").filter({ hasText: "OLD-1" });
  await row.getByRole("button", { name: "Restore" }).click();
  await expect(page.getByText("Nothing is archived.")).toBeVisible();
  await page.getByLabel("Show archived").uncheck();
  await expect(page.getByRole("link", { name: "OLD-1" })).toBeVisible();
});
