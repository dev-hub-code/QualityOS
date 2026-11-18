import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";
import { prepare, step } from "./trace-setup";

test("part of a lot is sent to another plant and received there as a lot of its own", async ({
  page,
}) => {
  await signUp(page);
  await prepare(page);
  const call = apiCaller(page);
  const today = new Date().toISOString().slice(0, 10);
  await call("POST", "/facilities", { code: "CHAKAN", name: "Chakan Plant" });
  const lots: { itemId: string; internalBatch: string }[] = await call(
    "GET",
    "/material-receipts/released-lots",
  );
  const lot = lots[0];

  await page.goto("/stock-transfers");
  await expect(
    page.getByText("No stock has moved between plants."),
  ).toBeVisible();
  await step(page, "Send stock", async (dialog: Locator) => {
    await dialog
      .getByLabel("From plant")
      .selectOption({ label: "PUNE – Pune Plant" });
    await dialog
      .getByLabel("To plant")
      .selectOption({ label: "CHAKAN – Chakan Plant" });
    await dialog.getByLabel("Sent on").fill(today);
    await dialog.getByLabel("Lot or batch 1").selectOption(`LOT:${lot.itemId}`);
    await dialog.getByLabel("Quantity 1").fill("40");
  });
  await expect(page.getByText("in transit")).toBeVisible();
  await expect(page.getByText("Pune Plant → Chakan Plant")).toBeVisible();

  await step(page, "Receive", async (dialog: Locator) => {
    await dialog.getByLabel("Received on").fill(today);
  });
  await expect(page.getByText("received", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: `${lot.internalBatch}-T1` }).click();
  await expect(page.getByText(`${lot.internalBatch}-T1`).first()).toBeVisible();

  const after: {
    itemId: string;
    internalBatch: string;
    remainingQuantity: string;
  }[] = await call("GET", "/material-receipts/released-lots");
  expect(
    Number(
      after.find((found) => found.itemId === lot.itemId)?.remainingQuantity,
    ),
  ).toBe(60);
  expect(
    Number(
      after.find((found) => found.internalBatch === `${lot.internalBatch}-T1`)
        ?.remainingQuantity,
    ),
  ).toBe(40);
});
