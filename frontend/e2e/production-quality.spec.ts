import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";
import { step } from "./trace-setup";

const day = (offset: number) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
const badge = (where: Page | Locator, text: string) => where.locator("[data-slot='badge']", { hasText: text }).first();

/**
 * Manufacturing phase 5, from the screens: a checkpoint becomes a gate on a released order, the gate's inspection
 * fails for rework, a rework order is approved, worked and closed on its reinspection, the gate passes on a second
 * inspection and the operation can start. Then a quality hold stops the batch.
 */
test("quality gates, rework and a quality hold on a work order", async ({ page, browser }) => {
  await signUp(page);
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const pcs = units.find((unit) => unit.code === "PCS")!.id;
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const product = await call("POST", "/products", { code: "SH-1", name: "Drive Shaft", productType: "FINISHED", uomId: pcs });
  await call("PUT", "/manufacturing/settings", { manufacturingModel: "DISCRETE", separateApprover: false, workOrderRequiresBom: false });
  const centre = await call("POST", "/manufacturing/work-centers", { code: "WC-1", name: "Turning", facilityId: facility.id });
  const routing = await call("POST", "/manufacturing/routings", { productId: product.id });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/operations`, {
    sequence: 10,
    name: "Turn shaft",
    workCenterId: centre.id,
    setupMinutes: "5",
    runMinutes: "1",
  });
  await call("POST", `/manufacturing/routings/${routing.revision.id}/submit`);
  await call("POST", `/manufacturing/routings/${routing.revision.id}/approve`, {});
  const created = await call("POST", "/inspection-templates", { code: "FIRST", name: "First-off check", inspectionType: "IPQC" });
  await call("PUT", `/inspection-templates/${created.template.id}/versions/${created.versions[0].id}`, {
    samplingType: "FIXED",
    sampleSize: 1,
    acceptanceNumber: 0,
    parameters: [
      {
        code: "DIA",
        name: "Diameter",
        dataType: "NUMERIC",
        criticality: "MAJOR",
        mandatory: true,
        perSample: true,
        evidenceRequired: false,
        minValue: "19.9",
        maxValue: "20.1",
      },
    ],
  });
  await call("POST", `/inspection-templates/${created.template.id}/publish`, { effectiveFrom: day(0) });

  // A second person approves inspections.
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);
  await manager.goto("/dashboard");
  const boss = apiCaller(manager);
  const decide = async (inspection: string, value: string, disposition: string) => {
    await call("POST", `/inspections/${inspection}/execute`, { results: [{ parameterCode: "DIA", sampleNumber: 1, valueNumeric: value }] });
    await call("POST", `/inspections/${inspection}/submit`);
    await boss("POST", `/inspections/${inspection}/approve`);
    await boss("POST", `/inspections/${inspection}/disposition`, { disposition });
  };

  // Quality sets the checkpoint.
  await page.goto("/manufacturing/quality-checkpoints");
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Quality checkpoints" })).toBeVisible();
  await step(page, "Add checkpoint", async (dialog) => {
    await dialog.getByLabel("Name").fill("First-off");
    await dialog.getByLabel("When").selectOption({ label: "Before the operation starts" });
    await dialog.getByLabel(/^Inspection template/).selectOption({ label: "IPQC · FIRST – First-off check" });
  });
  await expect(page.getByText("Before the operation starts · IPQC FIRST")).toBeVisible();

  const order = await call("POST", "/manufacturing/work-orders", {
    productId: product.id,
    facilityId: facility.id,
    quantity: "10",
    plannedStart: day(0),
    plannedEnd: day(2),
  });
  const orderPath = `/manufacturing/work-orders/${order.workOrder.id}`;
  await call("POST", `${orderPath}/submit`);
  await call("POST", `${orderPath}/approve`, {});
  await call("POST", `${orderPath}/release`);
  await call("POST", `${orderPath}/start`);

  // The gate is on the order; its inspection fails, and the goods go to rework.
  await page.goto(orderPath);
  const quality = page.locator("[data-slot='card']").filter({ hasText: "Quality gates" });
  await expect(quality.getByText("First-off on operation 10 is pending.")).toBeVisible();
  await quality.getByRole("button", { name: "Inspect", exact: true }).click();
  await expect(badge(quality, "in inspection")).toBeVisible();
  const gates = await call("GET", `${orderPath}/quality`);
  await decide(gates.gates[0].inspections[0].id, "25.0", "REWORK");
  await page.reload();
  await expect(quality.getByText(/is on hold for quality/)).toBeVisible();
  await expect(badge(quality, "failed")).toBeVisible();

  await step(page, "Raise rework order", async (dialog) => {
    await dialog.getByLabel("Inspection disposed of as rework").selectOption({ index: 1 });
    await dialog.getByLabel("Quantity").fill("2");
    await dialog.getByLabel("Reason").fill("Diameter oversize");
    await dialog.getByLabel("Steps, one per line").fill("Regrind diameter\nRe-measure");
  });
  await quality.getByRole("link", { name: /^RW-/ }).click();
  await expect(page.getByRole("heading", { name: /^RW-/ })).toBeVisible();

  await step(page, "Approve", async () => {});
  await page.getByRole("button", { name: "Start rework" }).click();
  await expect(badge(page, "in progress")).toBeVisible();
  for (let index = 0; index < 2; index++) {
    await page.getByRole("button", { name: "Mark done" }).first().click();
    await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("dialog")).toBeHidden();
  }
  await step(page, "Complete", async () => {});
  await expect(badge(page, "awaiting inspection")).toBeVisible();
  await step(page, "Reinspect", async (dialog) => {
    await dialog.getByLabel(/^Template/).selectOption({ label: "IPQC · FIRST – First-off check" });
  });
  const rework = await call("GET", `/manufacturing/rework-orders?workOrderId=${order.workOrder.id}`);
  const detail = await call("GET", `/manufacturing/rework-orders/${rework.items[0].id}`);
  await decide(detail.reinspection.id, "20.0", "ACCEPT");
  await page.reload();
  await page.getByRole("button", { name: "Close on the reinspection" }).click();
  await expect(badge(page, "closed")).toBeVisible();
  await expect(page.getByText("Reworked 2 PCS, scrapped 0 PCS")).toBeVisible();

  // The gate is inspected again and passes; the operation can start.
  await page.goto(orderPath);
  await expect(quality.getByText(/is on hold for quality/)).toBeHidden();
  await quality.getByRole("button", { name: "Inspect again" }).click();
  await expect(badge(quality, "in inspection")).toBeVisible();
  const again = await call("GET", `${orderPath}/quality`);
  await decide(again.gates[0].inspections[1].id, "20.0", "ACCEPT");
  await page.reload();
  await expect(badge(quality, "passed")).toBeVisible();
  await expect(quality.getByText(/reworked and accepted 2/)).toBeVisible();

  await page.getByRole("link", { name: /10\. Turn shaft/ }).click();
  const checks = page.locator("[data-slot='card']").filter({ hasText: "Quality checks" });
  await expect(badge(checks, "passed")).toBeVisible();
  await page.getByRole("button", { name: "Start", exact: true }).click();
  await expect(badge(page, "in progress")).toBeVisible();

  // A quality hold pauses what runs and stops the batch.
  await page.getByRole("link", { name: /^WO-/ }).click();
  await step(page, "Quality hold", async (dialog) => {
    await dialog.getByLabel("Why production must stop").fill("Gauge out of calibration");
  });
  await expect(quality.getByText(/is on hold for quality/)).toBeVisible();
  await page.getByRole("link", { name: /10\. Turn shaft/ }).click();
  await expect(page.getByText("The batch is on hold for quality.", { exact: false })).toBeVisible();
  await expect(page.getByText("Quality hold: Gauge out of calibration").first()).toBeVisible();
});
