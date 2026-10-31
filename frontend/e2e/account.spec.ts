import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";
import { acceptInvitation, inviteUser, linkFromEmail } from "./mail";

const PASSWORD = "correct-horse-battery";
const NEW_PASSWORD = "another-long-password";
const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

test("a forgotten password is reset by email", async ({ page, browser }) => {
  const email = `reset-${unique()}@example.com`;
  await signUp(page, email);

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

test("a new owner cannot sign in until their email is confirmed", async ({ page }) => {
  const email = `verify-${unique()}@example.com`;
  await page.goto("/register");
  await page.getByLabel("Organisation name").fill(`Acme ${unique()}`);
  await page.getByLabel("Your name").fill("Asha Rao");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create organisation" }).click();
  await expect(page.getByRole("heading", { name: "Check your inbox" })).toBeVisible();

  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Please confirm your email address first")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send a new link" })).toBeVisible();

  const link = await linkFromEmail(email, "/verify-email");
  await page.goto(link);
  await page.getByRole("button", { name: "Confirm email" }).click();
  await expect(page.getByText("Your email address is confirmed")).toBeVisible();

  await page.reload();
  await page.getByRole("button", { name: "Confirm email" }).click();
  await expect(page.getByText("This link is not valid or has expired")).toBeVisible();
  await expect(page.getByLabel("Email")).toBeVisible();
});

test("an invited person joins with their own password and sees no pending invitation left", async ({
  page,
  browser,
}) => {
  await signUp(page);
  const email = `invited-${unique()}@example.com`;
  await inviteUser(page, email, "Viewer");

  const invited = await acceptInvitation(browser, email, "Vikram Shah", PASSWORD);
  await expect(invited.getByRole("link", { name: "Users" })).toHaveCount(0);

  await page.reload();
  await expect(page.getByRole("cell", { name: email })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Pending invitations" })).toHaveCount(0);
});
