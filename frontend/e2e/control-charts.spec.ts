import { expect, test } from "@playwright/test";
import { apiCaller, signUp } from "./helpers";

/** Three diameters an inspection, steady around 20.00 but for one inspection that ran high. */
const DIAMETERS = Array.from({ length: 25 }, (_, index) => {
  const drift = index === 17 ? 0.06 : [0, 0.01, -0.01, 0.005, -0.005][index % 5];
  return [20 + drift, 20.01 + drift, 19.99 + drift].map((value) => value.toFixed(3));
});

test("control charts plot a parameter from submitted inspections, with Cp and Cpk", async ({ page }) => {
  test.setTimeout(180_000);
  await signUp(page);
  const call = apiCaller(page);
  const today = new Date().toISOString().slice(0, 10);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const facility = await call("POST", "/facilities", { code: "PUNE", name: "Pune Plant" });
  const material = await call("POST", "/materials", {
    code: "EN8",
    name: "EN8 Bar",
    uomId: units.find((unit) => unit.code === "PCS")?.id,
    inspectionRequired: true,
  });
  const supplier = await call("POST", "/suppliers", { code: "SUP", name: "Bharat Steels" });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);

  const created = await call("POST", "/inspection-templates", { code: "SHAFT", name: "Shaft IQC", inspectionType: "IQC" });
  const templateId: string = created.template.id;
  const detail = await call("GET", `/inspection-templates/${templateId}`);
  const draft = detail.versions.find((version: { status: string }) => version.status === "DRAFT");
  await call("PUT", `/inspection-templates/${templateId}/versions/${draft.id}`, {
    samplingType: "FIXED",
    sampleSize: 3,
    acceptanceNumber: 0,
    parameters: [
      {
        code: "DIA", name: "Diameter", dataType: "NUMERIC", criticality: "MAJOR", mandatory: true,
        perSample: true, evidenceRequired: false, minValue: "19.9", maxValue: "20.1",
      },
    ],
  });
  await call("POST", `/inspection-templates/${templateId}/publish`, { effectiveFrom: today });

  for (const [index, samples] of DIAMETERS.entries()) {
    const order = await call("POST", "/purchase-orders", {
      supplierId: supplier.id,
      facilityId: facility.id,
      orderDate: today,
      items: [{ materialId: material.id, quantity: "100" }],
    });
    await call("POST", `/purchase-orders/${order.id}/issue`);
    const receipt = await call("POST", "/material-receipts", {
      purchaseOrderId: order.id,
      receivedOn: today,
      items: [{ purchaseOrderItemId: order.items[0].id, supplierBatch: `HEAT-${index + 1}`, quantity: "100" }],
    });
    const inspection = await call("POST", "/inspections", { receiptItemId: receipt.items[0].id, templateId });
    await call("POST", `/inspections/${inspection.id}/execute`, {
      results: samples.map((value, sample) => ({ parameterCode: "DIA", sampleNumber: sample + 1, valueNumeric: value })),
    });
    await call("POST", `/inspections/${inspection.id}/submit`);
  }

  await page.goto("/analytics/control-charts");
  await expect(page.getByRole("heading", { name: "Control charts" })).toBeVisible();
  await expect(page.getByText("X̄-R chart, one subgroup per inspection")).toBeVisible();
  await expect(page.getByText("X̄ chart · subgroup means")).toBeVisible();
  await expect(page.getByText("R chart · subgroup ranges")).toBeVisible();
  await expect(page.getByText("Cpk", { exact: true })).toBeVisible();
  await expect(page.getByText(/mean above the upper control limit/)).toBeVisible();
  if (process.env.SPC_SCREENSHOT) await page.screenshot({ path: process.env.SPC_SCREENSHOT, fullPage: true });

  // The individuals chart takes every reading.
  await page.getByLabel("Chart", { exact: true }).selectOption("I_MR");
  await expect(page.locator("figcaption").getByText("Individuals", { exact: true })).toBeVisible();
  await expect(page.locator("figcaption").getByText("Moving range", { exact: true })).toBeVisible();
  if (process.env.SPC_SCREENSHOT) {
    await page.screenshot({ path: process.env.SPC_SCREENSHOT.replace(".png", "-imr.png"), fullPage: true });
  }

  // A signal opens its inspection.
  await page.getByRole("list", { name: "Signals" }).getByRole("link").first().click();
  await expect(page).toHaveURL(/\/inspections\//);
});
