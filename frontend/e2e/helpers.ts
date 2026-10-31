import { expect, type Page } from "@playwright/test";
import { linkFromEmail } from "./mail";

export const PASSWORD = "correct-horse-battery";
export const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

/** Registers, confirms the email address and signs in, which leaves the page on onboarding. */
export async function registerOwner(page: Page, email: string, organization = `Acme ${unique()}`) {
  await page.goto("/register");
  await page.getByLabel("Organisation name").fill(organization);
  await page.getByLabel("Your name").fill("Asha Rao");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create organisation" }).click();
  await expect(page.getByRole("heading", { name: "Check your inbox" })).toBeVisible();
  await page.goto(await linkFromEmail(email, "/verify-email"));
  await page.getByRole("button", { name: "Confirm email" }).click();
  await page.getByRole("link", { name: "Continue" }).click();
  await expect(page).toHaveURL(/login/);
  await page.getByLabel("Email").locator("visible=true").fill(email);
  await page.getByLabel("Password").locator("visible=true").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).locator("visible=true").click();
  await expect(page).toHaveURL(/onboarding/);
}

export async function finishOnboarding(page: Page) {
  await page.getByLabel("GSTIN").fill("27ABCDE1234F1Z5");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/dashboard/);
}

export async function signUp(page: Page, email = `owner-${unique()}@example.com`) {
  await registerOwner(page, email);
  await finishOnboarding(page);
}

/** Calls the API through the app's proxy, as the signed-in user. */
export function apiCaller(page: Page) {
  return (method: string, path: string, data: unknown = {}) =>
    page.evaluate(
      async ([method, path, data]) => {
        const init =
          method === "GET"
            ? {}
            : { method, headers: { "content-type": "application/json" }, body: JSON.stringify(data) };
        const response = await fetch(`/api/v1${path}`, init);
        return (await response.json()).data;
      },
      [method, path, data] as const,
    );
}
