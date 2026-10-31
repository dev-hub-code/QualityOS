import { expect, test } from "@playwright/test";
import { finishOnboarding, registerOwner } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

const PASSWORD = "correct-horse-battery";
const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

test("unauthenticated visitors are sent to sign in", async ({ page }) => {
  await page.goto("/users");

  await expect(page).toHaveURL(/login/);
});

test("register, onboard, sign out and sign back in", async ({ page }) => {
  const email = `owner-${unique()}@example.com`;
  await registerOwner(page, email);
  await finishOnboarding(page);
  await expect(page.getByRole("heading", { name: "Welcome, Asha Rao" })).toBeVisible();

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/login/);

  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("wrong-password-here");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Email or password is incorrect.")).toBeVisible();

  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/dashboard/);
});

test("tokens are not readable by page scripts", async ({ page, context }) => {
  await registerOwner(page, `owner-${unique()}@example.com`);

  expect(await page.evaluate(() => document.cookie)).not.toContain("qos_");
  const cookies = (await context.cookies()).filter((cookie) => cookie.name.startsWith("qos_"));
  expect(cookies).toHaveLength(2);
  expect(cookies.every((cookie) => cookie.httpOnly && cookie.sameSite === "Lax")).toBe(true);
});

test("an owner adds a viewer, whose navigation is restricted", async ({ page, browser }) => {
  await registerOwner(page, `owner-${unique()}@example.com`);
  await finishOnboarding(page);

  const viewerEmail = `viewer-${unique()}@example.com`;
  await inviteUser(page, viewerEmail, "Viewer");
  const viewer = await acceptInvitation(browser, viewerEmail, "Vikram Shah", PASSWORD);
  await expect(viewer.getByRole("link", { name: "Settings" })).toBeVisible();
  await expect(viewer.getByRole("link", { name: "Users" })).toHaveCount(0);

  await viewer.goto("/users");
  await expect(viewer.getByText("You do not have permission")).toBeVisible();
});

test("a custom role can be created", async ({ page }) => {
  await registerOwner(page, `owner-${unique()}@example.com`);
  await finishOnboarding(page);

  await page.getByRole("link", { name: "Roles" }).click();
  await page.getByRole("button", { name: "New role" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill("Night Shift Lead");
  await dialog.getByRole("checkbox", { name: "View non-conformances" }).click();
  await dialog.getByRole("button", { name: "Save role" }).click();

  await expect(page.getByRole("cell", { name: "Night Shift Lead" })).toBeVisible();
});

test("cross-site writes through the web tier are refused", async ({ page, request }) => {
  await registerOwner(page, `owner-${unique()}@example.com`);
  const cookie = (await page.context().cookies()).map((c) => `${c.name}=${c.value}`).join("; ");

  const response = await request.post("/api/v1/roles", {
    headers: { origin: "http://evil.example", cookie },
    data: { name: "Backdoor", permissions: ["USER_MANAGE"] },
  });

  expect(response.status()).toBe(403);
});
