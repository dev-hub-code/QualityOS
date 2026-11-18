import { expect, test } from "@playwright/test";
import { apiCaller, signUp, confirmAction } from "./helpers";

test("an administrator sets the organisation's review and NCR rules", async ({
  page,
}) => {
  await signUp(page);
  await page.goto("/settings/organisation");

  const capaRule = page.getByLabel(
    "A closed CAPA is needed before verification for",
  );
  await expect(capaRule).toHaveValue("MAJOR");
  await capaRule.selectOption({ label: "Critical NCRs only" });
  await expect(capaRule).toBeEnabled();

  const verificationRule = page.getByLabel(
    "A CAPA's fix must be verified as effective for",
  );
  await verificationRule.selectOption({ label: "Every NCR" });
  await expect(verificationRule).toBeEnabled();

  await page
    .getByRole("checkbox", {
      name: "Let me review my own work while I am the only member",
    })
    .click();
  await expect(
    page.getByRole("checkbox", {
      name: "Let me review my own work while I am the only member",
    }),
  ).toBeChecked();

  await page.reload();
  await expect(capaRule).toHaveValue("CRITICAL");
  await expect(verificationRule).toHaveValue("MINOR");
  await expect(
    page.getByRole("checkbox", {
      name: "Let me review my own work while I am the only member",
    }),
  ).toBeChecked();
});

// A 1×1 transparent PNG.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
  "base64",
);

test("an administrator uploads the organisation's logo, which the menu shows", async ({
  page,
}) => {
  await signUp(page);
  await page.goto("/settings");
  await expect(page.getByText("No logo yet.")).toBeVisible();

  await page.getByLabel("Logo file").setInputFiles({
    name: "notes.png",
    mimeType: "image/png",
    buffer: Buffer.from("not an image"),
  });
  await expect(
    page.getByText("The logo must be a PNG or JPEG image."),
  ).toBeVisible();

  await page
    .getByLabel("Logo file")
    .setInputFiles({ name: "logo.png", mimeType: "image/png", buffer: PNG });
  await expect(
    page.getByRole("img", { name: "Organisation logo" }),
  ).toBeVisible();
  const logo = page.locator("aside").getByRole("img", { name: / logo$/ });
  await expect(logo).toBeVisible();
  // The name sits beside the logo, on the same row.
  const name = (await logo.getAttribute("alt"))!.replace(/ logo$/, "");
  const label = page.locator("aside").getByText(name, { exact: true });
  await expect(label).toBeVisible();
  const [logoBox, labelBox] = [await logo.boundingBox(), await label.boundingBox()];
  expect(labelBox!.x).toBeGreaterThan(logoBox!.x + logoBox!.width - 1);
  expect(Math.abs(labelBox!.y + labelBox!.height / 2 - (logoBox!.y + logoBox!.height / 2))).toBeLessThan(8);

  await page.reload();
  await expect(
    page.getByRole("button", { name: "Replace logo" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Remove logo" }).click();
  await confirmAction(page);
  await expect(page.getByText("No logo yet.")).toBeVisible();
  await expect(
    page.locator("aside").getByRole("img", { name: / logo$/ }),
  ).toHaveCount(0);
});

test("an administrator renames NCR statuses, and NCRs show the new names", async ({
  page,
}) => {
  await signUp(page);
  await page.goto("/settings/organisation");
  await page.getByLabel("Open", { exact: true }).fill("Raised");
  await page.getByLabel("Containment", { exact: true }).fill("Quarantine");
  await page.getByRole("button", { name: "Save status names" }).click();
  await expect(page.getByText("Saved.")).toBeVisible();

  // Two statuses may not look alike.
  await page.getByLabel("Containment", { exact: true }).fill("closed");
  await page.getByRole("button", { name: "Save status names" }).click();
  await expect(
    page.getByText('Two statuses cannot share the name "Closed".'),
  ).toBeVisible();

  const raised = await apiCaller(page)("POST", "/ncrs", {
    title: "Burr on flange",
    description: "Found at final check",
    severity: "MINOR",
  });
  await page.goto("/ncrs");
  await expect(
    page
      .getByRole("row", { name: new RegExp(raised.ncrNumber) })
      .getByText("Raised", { exact: true }),
  ).toBeVisible();
  await page.goto(`/ncrs/${raised.id}`);
  await expect(page.getByText("Raised", { exact: true }).first()).toBeVisible();

  await page.goto("/settings/organisation");
  await expect(page.getByLabel("Containment", { exact: true })).toHaveValue(
    "Quarantine",
  );
});
