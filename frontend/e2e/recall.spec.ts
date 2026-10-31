import { expect, test, type Locator } from "@playwright/test";
import { unique } from "./helpers";
import { dispatchedBatch, step } from "./trace-setup";

test("a recall runs from the report through contact, acknowledgement and approved closure", async ({
  page,
  browser,
}) => {
  test.setTimeout(120_000);
  const serial = `SN-${unique()}`;
  const { batchNumber, manager } = await dispatchedBatch(page, browser, [serial]);
  const today = new Date().toISOString().slice(0, 10);

  await page.goto(`/traceability/${encodeURIComponent(batchNumber)}/recall`);
  await step(page, "Start a recall", async (dialog: Locator) => {
    await dialog.getByLabel("Recall date").fill(today);
    await dialog.getByLabel("Reason for recall").fill("Cracks found in the shaft");
    await dialog.getByLabel("What customers must do").fill("Stop use and return all units");
  });
  await expect(page.getByRole("heading", { name: /^RCL-/ })).toBeVisible();
  await expect(page.getByText("Affected customers")).toBeVisible();
  await expect(page.getByRole("listitem").getByText("Tata Motors")).toBeVisible();
  const recallUrl = page.url();

  await step(page, "Log attempt", async (dialog: Locator) => {
    await dialog.getByLabel("Method").selectOption("EMAIL");
    await dialog.getByLabel("Date").fill(today);
    await dialog.getByLabel("Outcome").selectOption("NO_REPLY");
  });
  await expect(page.getByRole("list").getByText("contact attempted")).toBeVisible();

  await step(page, "Log attempt", async (dialog: Locator) => {
    await dialog.getByLabel("Method").selectOption("PHONE");
    await dialog.getByLabel("Date").fill(today);
    await dialog.getByLabel("Outcome").selectOption("REACHED");
    await dialog.getByLabel("What they said").fill("Will check their stock");
  });
  await expect(page.getByRole("list").getByText("contacted", { exact: true })).toBeVisible();
  await expect(page.getByText("Will check their stock")).toBeVisible();

  await step(page, "Record acknowledgement", async (dialog: Locator) => {
    await dialog.getByLabel("Acknowledged on").fill(today);
    await dialog.getByLabel("Acknowledged by").fill("Asha Rao");
  });
  await expect(page.getByRole("list").getByText("acknowledged", { exact: true })).toBeVisible();
  await page.getByLabel("Move Tata Motors to").selectOption("RESOLVED");
  await expect(page.getByRole("list").getByText("resolved", { exact: true })).toBeVisible();

  await page.getByLabel("Filter by status").selectOption("PENDING");
  await expect(page.getByText("No customers match.")).toBeVisible();
  await page.getByLabel("Filter by status").selectOption("");

  await page.getByRole("link", { name: "Report" }).click();
  await expect(page.getByRole("heading", { name: /Product recall report/ })).toBeVisible();
  await expect(page.getByText(serial)).toBeVisible();
  await expect(page.getByRole("cell", { name: "Phone" })).toBeVisible();
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "Download PDF" }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/^RCL-.*\.pdf$/);
  const stream = await download.createReadStream();
  const first = await new Promise<Buffer>((resolve) => stream.once("data", resolve));
  expect(first.subarray(0, 5).toString()).toBe("%PDF-");

  await page.emulateMedia({ media: "print" });
  await expect(page.getByRole("navigation", { name: "Main" })).toBeHidden();
  await expect(page.getByRole("button", { name: "Print" })).toBeHidden();
  const printed = await page.pdf({ path: test.info().outputPath("recall-print.pdf"), preferCSSPageSize: true });
  expect(printed.subarray(0, 5).toString()).toBe("%PDF-");
  await page.emulateMedia({ media: "screen" });

  await page.goto(recallUrl);
  await step(page, "Request closure", async (dialog: Locator) => {
    await dialog.getByLabel("Closure note").fill("Customer holds stock under quarantine");
    await dialog.getByLabel(/I accept that goods remain/).click();
  });
  await expect(page.getByRole("button", { name: "Approve closure" })).toBeVisible();

  await page.getByRole("button", { name: "Approve closure" }).click();
  await expect(page.getByText("Someone else must approve closing this recall.")).toBeVisible();

  await manager.goto(recallUrl);
  await manager.getByRole("button", { name: "Approve closure" }).click();
  await expect(manager.getByRole("button", { name: "Approve closure" })).toHaveCount(0);
  await expect(manager.getByText("closed", { exact: true }).first()).toBeVisible();
  await expect(manager.getByRole("button", { name: "Log attempt" })).toHaveCount(0);
});
