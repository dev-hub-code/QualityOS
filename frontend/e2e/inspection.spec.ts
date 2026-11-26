import { expect, test, type Locator, type Page } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique, confirmAction } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

async function dialogAdd(
  page: Page,
  button: string,
  fill: (page: Page) => Promise<void>,
) {
  await page.getByRole("button", { name: button }).click();
  await fill(page);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeHidden();
}

/** Facility, material, approved supplier, issued order and received batch, through the API proxy. */
async function receiveMaterial(page: Page) {
  const call = apiCaller(page);
  const units: { id: string; code: string }[] = await call("GET", "/units");
  const facility = await call("POST", "/facilities", {
    code: "PUNE",
    name: "Pune Plant",
  });
  const material = await call("POST", "/materials", {
    code: "EN8",
    name: "EN8 Bar",
    uomId: units.find((unit) => unit.code === "PCS")?.id,
    inspectionRequired: true,
  });
  const supplier = await call("POST", "/suppliers", {
    code: "SUP",
    name: "Bharat Steels",
  });
  await call("POST", `/suppliers/${supplier.id}/submit`);
  await call("POST", `/suppliers/${supplier.id}/approve`);
  const order = await call("POST", "/purchase-orders", {
    supplierId: supplier.id,
    facilityId: facility.id,
    orderDate: new Date().toISOString().slice(0, 10),
    items: [{ materialId: material.id, quantity: "100" }],
  });
  await call("POST", `/purchase-orders/${order.id}/issue`);
  await call("POST", "/material-receipts", {
    purchaseOrderId: order.id,
    receivedOn: new Date().toISOString().slice(0, 10),
    items: [
      {
        purchaseOrderItemId: order.items[0].id,
        supplierBatch: "HEAT-1",
        quantity: "100",
      },
    ],
  });
}

test("a template is published, and a failed critical measurement never releases material", async ({
  page,
  browser,
}) => {
  test.setTimeout(90_000);
  await signUp(page);
  await receiveMaterial(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(
    browser,
    managerEmail,
    "Meera Iyer",
    PASSWORD,
  );

  await page.goto("/inspection-templates");
  await dialogAdd(page, "Add template", async (dialog) => {
    await dialog.getByLabel("Code", { exact: true }).fill("BAR-IQC");
    await dialog.getByLabel("Name", { exact: true }).fill("Bar incoming");
    await dialog.getByLabel("Type").selectOption("IQC");
  });
  await expect(page).toHaveURL(/inspection-templates\//);

  await page.getByLabel("Sample size").fill("2");
  await page.getByRole("button", { name: "Add parameter" }).click();
  await page.getByLabel("Parameter code").fill("DIA");
  await page.getByLabel("Parameter name").fill("Diameter");
  await page.getByLabel("Minimum").fill("19.9");
  await page.getByLabel("Maximum").fill("20.1");
  await page.getByLabel("Criticality").selectOption("CRITICAL");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.getByRole("button", { name: "Publish" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "New version" })).toBeVisible();
  await expect(page.getByText("19.9 to 20.1")).toBeVisible();
  await page.getByRole("button", { name: "Edit" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill("Bar stock incoming");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(
    page.getByRole("heading", { name: "Bar stock incoming" }),
  ).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/inspections");
  await page.getByRole("button", { name: "Inspect" }).click();
  await page
    .getByLabel("Template")
    .selectOption({ label: "BAR-IQC – Bar stock incoming" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);

  await page.getByLabel("DIA sample 1").fill("20.0");
  await page.getByLabel("DIA sample 2").fill("25");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await confirmAction(page);
  await expect(
    page.getByText("submitted", { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByText("hold", { exact: true }).first()).toBeVisible();
  await expect(
    page.getByText("Someone other than the inspector"),
  ).toBeVisible();
  const url = page.url();

  await manager.goto("/notifications");
  await manager.getByRole("button", { name: /Critical failure/ }).click();
  await expect(manager).toHaveURL(url);
  await expect(
    manager.getByRole("link", { name: "Notifications", exact: true }).first(),
  ).toBeAttached();
  await manager.getByRole("button", { name: "Approve" }).click();
  await manager
    .getByRole("dialog")
    .getByRole("button", { name: "Save" })
    .click();
  await manager.getByRole("button", { name: "Record disposition" }).click();

  const disposition = manager
    .getByRole("dialog")
    .getByLabel("Disposition", { exact: true });
  await expect(
    disposition.locator("option", { hasText: "Reject" }),
  ).toBeAttached();
  expect(await disposition.locator("option").allTextContents()).not.toContain(
    "Accept",
  );
  await disposition.selectOption("REJECT");
  await manager
    .getByRole("dialog")
    .getByRole("button", { name: "Save" })
    .click();
  await expect(
    manager.getByText("completed", { exact: true }).first(),
  ).toBeVisible();

  await manager.getByRole("link", { name: "Report", exact: true }).click();
  await expect(
    manager.getByRole("heading", { name: /Inspection report/ }),
  ).toBeVisible();
  await expect(manager.getByText("19.9 to 20.1").first()).toBeVisible();
  const pdf = manager.waitForEvent("download");
  await manager.getByRole("link", { name: "Download PDF" }).click();
  expect((await pdf).suggestedFilename()).toMatch(/^INS-.*\.pdf$/);

  // The failure now has an NCR, which runs through a verified CAPA to closure.
  await manager.goto(url);
  await manager.getByRole("link", { name: /^NCR-/ }).click();
  await expect(manager).toHaveURL(/ncrs\//);
  const ncrUrl = manager.url();

  const step = async (
    who: Page,
    button: string,
    fill?: (dialog: Locator) => Promise<void>,
  ) => {
    await who.getByRole("button", { name: button, exact: true }).click();
    const dialog = who.getByRole("dialog");
    await fill?.(dialog);
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toBeHidden();
  };

  await manager.goto("/defect-categories");
  await expect(manager).toHaveURL(/\/categories$/);
  await step(manager, "Add defect category", async (dialog) => {
    await dialog.getByLabel("Code").fill("DIM");
    await dialog.getByLabel("Name").fill("Dimensional");
  });
  await expect(manager.getByText("DIM – Dimensional")).toBeVisible();

  await manager.goto(ncrUrl);
  await step(manager, "Assign", (dialog) =>
    dialog
      .getByLabel("Assign to")
      .selectOption({ label: "Asha Rao" })
      .then(() => {}),
  );
  await step(manager, "Record containment", (dialog) =>
    dialog.getByLabel("Containment action").fill("Lot quarantined"),
  );
  await step(manager, "Record root cause", async (dialog) => {
    await dialog.getByLabel("Root cause").fill("Heat treatment drift");
    await dialog
      .getByLabel("Defect category")
      .selectOption({ label: "DIM – Dimensional" });
  });

  await manager.getByRole("button", { name: "Request verification" }).click();
  await manager
    .getByRole("dialog")
    .getByRole("button", { name: "Save" })
    .click();
  await expect(
    manager.getByRole("dialog").getByText("needs a closed CAPA"),
  ).toBeVisible();
  await manager.goto(ncrUrl);

  await manager.getByRole("button", { name: "Raise CAPA" }).click();
  await manager
    .getByRole("dialog")
    .getByLabel("Owner")
    .selectOption({ label: "Asha Rao" });
  await manager
    .getByRole("dialog")
    .getByLabel("Target date")
    .fill("2036-01-15");
  await manager
    .getByRole("dialog")
    .getByRole("button", { name: "Save" })
    .click();
  await expect(manager).toHaveURL(/capas\//);
  const capaUrl = manager.url();

  await page.goto(capaUrl);
  await page.getByRole("button", { name: "Start analysis" }).click();
  await page.getByLabel("Why 1").fill("Diameter oversize");
  await page.getByLabel("Why 2").fill("Furnace drift");
  await page.getByLabel("Machine").fill("Thermocouple aged");
  await page
    .getByLabel("Root cause")
    .fill("No thermocouple replacement schedule");
  await page.getByRole("button", { name: "Finish analysis" }).click();
  await confirmAction(page);
  await step(page, "Add action", async (dialog) => {
    await dialog.getByLabel("Type").selectOption("CORRECTIVE");
    await dialog.getByLabel("Description").fill("Replace thermocouple");
    await dialog.getByLabel("Owner").selectOption({ label: "Asha Rao" });
    await dialog.getByLabel("Target date").fill("2036-01-10");
  });
  await page.getByRole("button", { name: "Start implementation" }).click();
  // The organisation requires evidence on every action before it is completed.
  await page.goto("/settings/organisation");
  await page
    .getByLabel(
      "A CAPA action needs evidence attached before it can be completed",
    )
    .click();
  await expect(
    page.getByLabel(
      "A CAPA action needs evidence attached before it can be completed",
    ),
  ).toBeChecked();
  await page.goto(capaUrl);
  await expect(
    page.getByText("Evidence (required before it can be completed)"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Complete", exact: true }).click();
  await page
    .getByRole("dialog")
    .getByLabel("What was done")
    .fill("Replaced and calibrated");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(
    page
      .getByRole("dialog")
      .getByText("Attach evidence to this action before completing it."),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  // Evidence belongs to the action it proves.
  await page
    .getByLabel("Upload evidence for: Replace thermocouple")
    .setInputFiles({
      name: "thermocouple-cert.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4 calibration certificate"),
    });
  await expect(
    page.getByRole("link", { name: "thermocouple-cert.pdf" }),
  ).toBeVisible();
  await step(page, "Complete", (dialog) =>
    dialog.getByLabel("What was done").fill("Replaced and calibrated"),
  );
  await page.getByRole("button", { name: "Submit for verification" }).click();
  await expect(
    page.getByText("Someone other than the CAPA owner"),
  ).toBeVisible();

  await manager.goto(capaUrl);
  await step(manager, "Verify effectiveness", async (dialog) => {
    await dialog.getByLabel("Result").selectOption("true");
    await dialog
      .getByLabel("How it was checked")
      .fill("Next three lots measured");
    await dialog.getByLabel("Findings").fill("All within limits");
  });
  await step(manager, "Close CAPA", (dialog) =>
    dialog.getByLabel("Closure note").fill("Effective"),
  );
  await expect(
    manager.getByText("closed", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    manager.getByRole("link", { name: "thermocouple-cert.pdf" }),
  ).toBeVisible();
  await expect(
    manager.getByLabel("Upload evidence for: Replace thermocouple"),
  ).toHaveCount(0);

  await page.goto(ncrUrl);
  await step(page, "Request verification");
  await manager.goto(ncrUrl);
  await step(manager, "Close NCR", (dialog) =>
    dialog.getByLabel("Closure note").fill("Verified on the floor"),
  );
  await expect(
    manager.getByText("closed", { exact: true }).first(),
  ).toBeVisible();
  await expect(manager.getByRole("button", { name: "Raise CAPA" })).toHaveCount(
    0,
  );
});

test("a template can sample by AQL at a special level, set from the lot size each time", async ({
  page,
}) => {
  await signUp(page);
  await page.goto("/inspection-templates");
  await dialogAdd(page, "Add template", async (dialog) => {
    await dialog.getByLabel("Code", { exact: true }).fill("BAR-AQL");
    await dialog.getByLabel("Name", { exact: true }).fill("Bar by AQL");
    await dialog.getByLabel("Type").selectOption("IQC");
  });
  await expect(page).toHaveURL(/inspection-templates\//);

  await page.getByLabel("Sampling").selectOption("AQL");
  await expect(page.getByLabel("Failures allowed per parameter")).toHaveCount(
    0,
  );
  await page.getByLabel("AQL (% nonconforming)").selectOption("1.0");
  await page.getByLabel("Inspection level").selectOption("S3");
  await page.getByLabel("Double sampling", { exact: false }).check();
  await page.getByRole("button", { name: "Add parameter" }).click();
  await page.getByLabel("Parameter code").fill("DIA");
  await page.getByLabel("Parameter name").fill("Diameter");
  await page.getByLabel("Minimum").fill("19.9");
  await page.getByLabel("Maximum").fill("20.1");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("AQL (% nonconforming)")).toHaveValue("1.0");
  await expect(page.getByLabel("Inspection level")).toHaveValue("S3");
  await expect(
    page.getByLabel("Double sampling", { exact: false }),
  ).toBeChecked();
  await page.getByRole("button", { name: "Publish" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(
    page.getByText(
      "AQL 1, inspection level S-3, double sampling, with sample size and acceptance set by each lot's size",
    ),
  ).toBeVisible();
  // Every series starts on normal inspection; the switching rules take it from there.
  await expect(page.getByText("AQL switching", { exact: true })).toBeVisible();
  await expect(
    page.getByText("No lot has been decided under this template yet", {
      exact: false,
    }),
  ).toBeVisible();
});

test("under double sampling an inconclusive first sample asks for a second, counted with it", async ({
  page,
}) => {
  await signUp(page);
  await receiveMaterial(page);
  await page.goto("/inspection-templates");
  await dialogAdd(page, "Add template", async (dialog) => {
    await dialog.getByLabel("Code", { exact: true }).fill("BAR-DBL");
    await dialog
      .getByLabel("Name", { exact: true })
      .fill("Bar by double sampling");
    await dialog.getByLabel("Type").selectOption("IQC");
  });
  await expect(page).toHaveURL(/inspection-templates\//);
  // A lot of 100 at level II and AQL 4.0 is letter F: two samples of 13, the first accepting none.
  await page.getByLabel("Sampling").selectOption("AQL");
  await page.getByLabel("AQL (% nonconforming)").selectOption("4.0");
  await page.getByLabel("Inspection level").selectOption("II");
  await page.getByLabel("Double sampling", { exact: false }).check();
  await page.getByRole("button", { name: "Add parameter" }).click();
  await page.getByLabel("Parameter code").fill("DIA");
  await page.getByLabel("Parameter name").fill("Diameter");
  await page.getByLabel("Minimum").fill("19.9");
  await page.getByLabel("Maximum").fill("20.1");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.getByRole("button", { name: "Publish" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "New version" })).toBeVisible();

  await page.goto("/inspections");
  await page.getByRole("button", { name: "Inspect" }).click();
  await page
    .getByLabel("Template")
    .selectOption({ label: "BAR-DBL – Bar by double sampling" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
  await expect(
    page.getByText(
      /double sampling\. First sample: accept up to 0 nonconforming, reject from 3/,
    ),
  ).toBeVisible();
  await expect(page.getByLabel("DIA sample 14")).toHaveCount(0);

  for (let sample = 1; sample <= 13; sample++) {
    await page
      .getByLabel(`DIA sample ${sample}`, { exact: true })
      .fill(sample === 1 ? "25" : "20.0");
  }
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await confirmAction(page);
  await expect(
    page.getByRole("status").filter({ hasText: "a second sample of 13 is" }),
  ).toBeVisible();
  for (let sample = 14; sample <= 26; sample++) {
    await page.getByLabel(`DIA sample ${sample}`, { exact: true }).fill("20.0");
  }
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await confirmAction(page);
  await expect(
    page.getByText("submitted", { exact: true }).first(),
  ).toBeVisible();
});

/** Signs up, publishes a one-parameter template and starts an inspection with it, leaving its page open. */
async function startFloorInspection(page: Page) {
  await signUp(page);
  await receiveMaterial(page);
  await page.goto("/inspection-templates");
  await dialogAdd(page, "Add template", async (dialog) => {
    await dialog.getByLabel("Code", { exact: true }).fill("BAR-OFF");
    await dialog.getByLabel("Name", { exact: true }).fill("Bar on the floor");
    await dialog.getByLabel("Type").selectOption("IQC");
  });
  await expect(page).toHaveURL(/inspection-templates\//);
  await page.getByRole("button", { name: "Add parameter" }).click();
  await page.getByLabel("Parameter code").fill("DIA");
  await page.getByLabel("Parameter name").fill("Diameter");
  await page.getByLabel("Minimum").fill("19.9");
  await page.getByLabel("Maximum").fill("20.1");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Saved")).toBeVisible();
  await page.getByRole("button", { name: "Publish" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "New version" })).toBeVisible();

  await page.goto("/inspections");
  await page.getByRole("button", { name: "Inspect" }).click();
  await page
    .getByLabel("Template")
    .selectOption({ label: "BAR-OFF – Bar on the floor" });
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page).toHaveURL(/inspections\//);
}

test("results saved without a connection are kept on the device and sent when it is back", async ({
  page,
  context,
}) => {
  await startFloorInspection(page);

  await context.setOffline(true);
  await page.getByLabel("DIA sample 1", { exact: true }).fill("20.02");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(
    page.getByText("The values are kept on this device", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("On this device, not sent yet")).toBeVisible();
  await expect(
    page.getByText("No connection. Inspection results you save are kept on this device", {
      exact: false,
    }),
  ).toBeVisible();

  await context.setOffline(false);
  await expect(page.getByText("On this device, not sent yet")).toHaveCount(0);
  await expect(page.getByText("waiting to be sent")).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("DIA sample 1", { exact: true })).toHaveValue(
    "20.02",
  );
});

test("values kept offline for an inspection submitted meanwhile are refused with the reason, and discarding them asks first", async ({
  page,
  context,
  browser,
}) => {
  await startFloorInspection(page);
  const inspectionUrl = page.url();

  await context.setOffline(true);
  await page.getByLabel("DIA sample 1", { exact: true }).fill("19.95");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByText("On this device, not sent yet")).toBeVisible();

  // Meanwhile, from the bench, the same person finishes and submits the inspection.
  const bench = await browser.newContext({
    storageState: await context.storageState(),
  });
  const benchPage = await bench.newPage();
  await benchPage.goto(inspectionUrl);
  const samples = benchPage.getByLabel(/^DIA sample \d+$/);
  for (let index = 0; index < (await samples.count()); index++) {
    await samples.nth(index).fill("20.0");
  }
  await benchPage.getByRole("button", { name: "Submit for approval" }).click();
  await confirmAction(benchPage);
  await expect(
    benchPage.getByText("submitted", { exact: true }).first(),
  ).toBeVisible();
  await bench.close();

  // Back online, the tablet's value is refused and kept with the reason, not lost and not sent again on its own.
  await context.setOffline(false);
  await expect(
    page.getByText("not accepted: Results cannot be changed after submission."),
  ).toBeVisible();

  await page.getByRole("button", { name: "Discard" }).click();
  const question = page.getByRole("alertdialog");
  await expect(question).toContainText("exist only on this device");
  await question.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByText("not accepted:", { exact: false })).toBeVisible();

  await page.getByRole("button", { name: "Discard" }).click();
  await confirmAction(page);
  await expect(page.getByText("not accepted:", { exact: false })).toHaveCount(
    0,
  );
});
