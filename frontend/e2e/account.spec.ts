import { expect, test } from "@playwright/test";
import { acceptInvitation, inviteUser, linkFromEmail } from "./mail";

const PASSWORD = "correct-horse-battery";
const NEW_PASSWORD = "another-long-password";
const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function registerOwner(page: import("@playwright/test").Page, email: string) {
  await page.goto("/register");
  await page.getByLabel("Organisation name").fill(`Acme ${unique()}`);
  await page.getByLabel("Your name").fill("Asha Rao");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create organisation" }).click();
  await expect(page).toHaveURL(/onboarding/);
  await page.getByLabel("GSTIN").fill("27ABCDE1234F1Z5");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/dashboard/);
}

test("a forgotten password is reset by email", async ({ page, browser }) => {
  const email = `reset-${unique()}@example.com`;
  await registerOwner(page, email);

  const visitor = await (await browser.newContext()).newPage();
  await visitor.goto("/login");
  await visitor.getByRole("link", { name: "Forgot your password?" }).click();
  // The sign-in page stays mounted but hidden, so wait for the swap and then use the visible field.
  await expect(visitor.getByRole("heading", { name: "Reset your password" })).toBeVisible();
  await visitor.getByLabel("Email").locator("visible=true").fill(email);
  await visitor.getByRole("button", { name: "Send reset link" }).click();
  await expect(visitor.getByText("a reset link is on its way")).toBeVisible();

  await visitor.goto(await linkFromEmail(email, "/reset-password"));
  await visitor.getByLabel("New password").fill(NEW_PASSWORD);
  await visitor.getByRole("button", { name: "Change password" }).click();
  await expect(visitor.getByText("Your password has changed")).toBeVisible();

  await visitor.goto("/login");
  await visitor.getByLabel("Email").fill(email);
  await visitor.getByLabel("Password").fill(NEW_PASSWORD);
  await visitor.getByRole("button", { name: "Sign in" }).click();
  await expect(visitor).toHaveURL(/dashboard/);
});

test("a new owner confirms their email from the link", async ({ page }) => {
  const email = `verify-${unique()}@example.com`;
  await registerOwner(page, email);
  await expect(page.getByText("Please confirm your email address")).toBeVisible();

  await page.goto(await linkFromEmail(email, "/verify-email"));
  await page.getByRole("button", { name: "Confirm email" }).click();
  await expect(page.getByText("Your email address is confirmed")).toBeVisible();

  await page.getByRole("link", { name: "Continue" }).click();
  await expect(page).toHaveURL(/dashboard/);
  await expect(page.getByText("Please confirm your email address")).toHaveCount(0);
});

test("an invited person joins with their own password and sees no pending invitation left", async ({
  page,
  browser,
}) => {
  await registerOwner(page, `owner-${unique()}@example.com`);
  const email = `invited-${unique()}@example.com`;
  await inviteUser(page, email, "Viewer");

  const invited = await acceptInvitation(browser, email, "Vikram Shah", PASSWORD);
  await expect(invited.getByRole("link", { name: "Users" })).toHaveCount(0);

  await page.reload();
  await expect(page.getByRole("cell", { name: email })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Pending invitations" })).toHaveCount(0);
});
