import { expect, test } from "@playwright/test";
import { signUp } from "./helpers";
import { acceptInvitation, emailWithSubject, inviteUser, linkFromEmail } from "./mail";

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
  await expect(page.getByRole("heading", { name: "Confirm your email address" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Send a new link" })).toBeVisible();

  // The restricted session is an HttpOnly cookie, cannot be refreshed, and opens nothing.
  const cookies = await page.context().cookies();
  expect(cookies.find((cookie) => cookie.name === "qos_access")?.httpOnly).toBe(true);
  expect(cookies.find((cookie) => cookie.name === "qos_refresh")).toBeUndefined();
  await page.goto("/dashboard");
  await expect(page).toHaveURL(/login/);
  expect(await page.evaluate(() => fetch("/api/v1/auth/me").then((response) => response.status))).toBe(403);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();

  // Confirming in another tab shows up here, through the restricted session.
  const link = await linkFromEmail(email, "/verify-email");
  const mailTab = await page.context().newPage();
  await mailTab.goto(link);
  await mailTab.getByRole("button", { name: "Confirm email" }).click();
  await expect(mailTab.getByText("Your email address is confirmed")).toBeVisible();
  await expect(page.getByText("Your email is confirmed. You can sign in now.")).toBeVisible({ timeout: 10_000 });
  await mailTab.close();

  await page.goto(link);

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

test("a user who chooses Hindi receives a Hindi HTML email with a plain text part", async ({ page, browser }) => {
  const email = `hindi-${unique()}@example.com`;
  await signUp(page, email);
  await page.goto("/settings");
  const saved = page.waitForResponse((response) => response.url().includes("/email-language"));
  await page.getByLabel("Send my emails in").selectOption("hi");
  expect((await saved).status()).toBe(204);
  await page.reload();
  await expect(page.getByLabel("Send my emails in")).toHaveValue("hi");

  const visitor = await (await browser.newContext()).newPage();
  await visitor.goto("/forgot-password");
  await visitor.getByLabel("Email").fill(email);
  await visitor.getByRole("button", { name: /send/i }).click();

  const message = await emailWithSubject(email, "अपना QualityOS पासवर्ड रीसेट करें");
  expect(message.HTML).toContain('<html lang="hi">');
  expect(message.HTML).toContain("पासवर्ड रीसेट करें");
  expect(message.Text).toMatch(/reset-password\?token=/);
  expect(message.Text).not.toContain("<p");
});
