import { expect, test, type Locator } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";
import { step } from "./trace-setup";

test("an audit runs from checklist to a finding's NCR, a report, and closure by a second person", async ({
  page,
  browser,
}) => {
  test.setTimeout(90_000);
  await signUp(page);
  const email = `manager-${unique()}@example.com`;
  await inviteUser(page, email, "Quality Manager");
  const manager = await acceptInvitation(browser, email, "Meera Iyer", PASSWORD);
  const owner = apiCaller(page);
  await owner("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });

  await page.goto("/audit-checklists");
  await step(page, "New checklist", async (dialog: Locator) => {
    await dialog.getByLabel("Code").fill("SHOP");
    await dialog.getByLabel("Name").fill("Shop floor");
    await dialog.getByLabel(/Questions/).fill("Are gauges labelled? | 7.1.5\nAre records kept?");
  });
  await expect(page.getByRole("cell", { name: "Shop floor" })).toBeVisible();

  await page.goto("/audits");
  await page.getByRole("button", { name: "Plan audit" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Type").selectOption("INTERNAL");
  await dialog.getByLabel("Title").fill("Machining audit");
  await dialog.getByLabel("Scope").fill("Line 2");
  await dialog.getByLabel("Facility").selectOption({ label: "PUNE – Pune Plant" });
  await dialog.getByLabel("Checklist", { exact: true }).selectOption({ label: "SHOP – Shop floor" });
  await dialog.getByLabel("Planned for").fill(new Date().toISOString().slice(0, 10));
  await dialog.getByLabel("Lead auditor").selectOption({ label: "Meera Iyer" });
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("heading", { name: /^AUD-/ })).toBeVisible();
  const auditUrl = page.url();

  await page.getByRole("button", { name: "Start audit" }).click();
  await page.getByLabel("Answer to question 1").selectOption("NONCONFORMING");
  await expect(page.getByText("Checklist (1 of 2 answered)")).toBeVisible();
  await page.getByLabel("Answer to question 2").selectOption("CONFORMING");
  await expect(page.getByText("Checklist (2 of 2 answered)")).toBeVisible();

  await page.getByRole("button", { name: "Complete audit" }).click();
  await page.getByRole("dialog").getByLabel("Summary").fill("Reviewed line 2");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("dialog").getByText("Question 1 is nonconforming and needs a finding.")).toBeVisible();
  await page.keyboard.press("Escape");

  await step(page, "Record finding", async (form: Locator) => {
    await form.getByLabel("Grade").selectOption("MAJOR_NONCONFORMITY");
    await form.getByLabel("Checklist question").selectOption({ index: 1 });
    await form.getByLabel("Finding", { exact: true }).fill("Gauge unlabelled");
    await form.getByLabel("Objective evidence").fill("Caliper VC-9 had no label");
  });
  const ncrLink = page.getByRole("link", { name: /^NCR-/ });
  await expect(ncrLink).toBeVisible();
  await page.getByRole("button", { name: "Attachments" }).click();
  await page.getByLabel("Upload a document").setInputFiles({
    name: "station-4.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7 evidence"),
  });
  await expect(page.getByText("station-4.pdf")).toBeVisible();

  await step(page, "Complete audit", async (form: Locator) => {
    await form.getByLabel("Summary").fill("Reviewed line 2");
  });
  await expect(page.getByText(/is still open/)).toBeVisible();

  await page.getByRole("link", { name: "Report" }).click();
  await expect(page.getByRole("heading", { name: /Quality audit report/ })).toBeVisible();
  await expect(page.getByText(/not a certificate of compliance/)).toBeVisible();
  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("link", { name: "Download PDF" }).click(),
  ]);
  expect(download.suggestedFilename()).toMatch(/^AUD-.*\.pdf$/);

  await page.goto(auditUrl);
  await ncrLink.click();
  await expect(page.getByRole("link", { name: "Audit", exact: true })).toBeVisible();
  const ncrId = page.url().split("/").pop();
  await owner("POST", `/ncrs/${ncrId}/cancel`, { note: "Label found in the drawer" });

  await page.goto(auditUrl);
  await step(page, "Request closure", async (form: Locator) => {
    await form.getByLabel("Closure note").fill("Nothing left open");
  });
  await page.getByRole("button", { name: "Approve closure" }).click();
  await expect(page.getByText("Someone else must approve closing this audit.")).toBeVisible();

  await manager.goto(auditUrl);
  await manager.getByRole("button", { name: "Approve closure" }).click();
  await expect(manager.getByText("closed", { exact: true }).first()).toBeVisible();
  await expect(manager.getByRole("button", { name: "Approve closure" })).toHaveCount(0);
});

test("audit and calibration pages stay closed to other organisations", async ({ page, browser }) => {
  await signUp(page);
  const owner = apiCaller(page);
  const facility = await owner("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const instrument = await owner("POST", "/instruments", {
    instrumentCode: "VC-1", name: "Caliper", category: "Dimensional", facilityId: facility.id,
    frequencyDays: 180, nextDueOn: new Date().toISOString().slice(0, 10),
  });

  const anonymous = await browser.newPage();
  await anonymous.goto(`/calibration/${instrument.id}`);
  await expect(anonymous).toHaveURL(/\/login/);
  await anonymous.close();

  const context = await browser.newContext();
  const stranger = await context.newPage();
  await signUp(stranger);
  await stranger.goto(`/calibration/${instrument.id}`);
  await expect(stranger.getByText("This record does not exist.")).toBeVisible();
  await context.close();
});
