import { expect, test } from "@playwright/test";
import { apiCaller, signUp, unique } from "./helpers";

test("a process incident is raised against a machine, and the NCR says where it happened", async ({
  page,
}) => {
  await signUp(page);
  const call = apiCaller(page);
  const facility = await call("POST", "/facilities", {
    code: `P-${unique()}`.slice(0, 20),
    name: "Press Plant",
  });
  const centre = await call("POST", "/manufacturing/work-centers", {
    code: "WC-P",
    name: "Pressing",
    facilityId: facility.id,
  });
  await call("POST", "/manufacturing/machines", {
    code: "M-7",
    name: "Press 7",
    workCenterId: centre.id,
  });

  await page.goto("/ncrs");
  await page.getByRole("button", { name: "Process incident" }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("heading", { name: "Raise a process incident" }),
  ).toBeVisible();
  await expect(dialog.getByLabel("Supplier")).toHaveCount(0);
  await dialog.getByLabel("Title").fill("Hydraulic oil on the floor");
  await dialog
    .getByLabel("Description")
    .fill("Leak from the main cylinder seal");
  await dialog.getByLabel("Severity").selectOption("MAJOR");
  await dialog.getByLabel("Machine").selectOption({ label: "M-7 – Press 7" });
  await dialog.getByRole("button", { name: "Save" }).click();

  await expect(page).toHaveURL(/ncrs\/[0-9a-f-]{36}/);
  await expect(
    page.locator("dd", { hasText: /^Process incident$/ }),
  ).toBeVisible();
  await expect(page.getByText("Machine M-7 – Press 7")).toBeVisible();
});

test("a supplier issue cannot be saved without its supplier", async ({
  page,
}) => {
  await signUp(page);
  await page.goto("/ncrs");
  await page.getByRole("button", { name: "Supplier issue" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Title").fill("Short delivery");
  await dialog.getByLabel("Description").fill("Two cartons missing");
  await dialog.getByLabel("Severity").selectOption("MINOR");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog.getByText("Supplier is required")).toBeVisible();
});
