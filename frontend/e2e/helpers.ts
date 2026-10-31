import { expect, type Page } from "@playwright/test";

export const PASSWORD = "correct-horse-battery";
export const unique = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

export async function signUp(page: Page) {
  await page.goto("/register");
  await page.getByLabel("Organisation name").fill(`Acme ${unique()}`);
  await page.getByLabel("Your name").fill("Asha Rao");
  await page.getByLabel("Work email").fill(`owner-${unique()}@example.com`);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create organisation" }).click();
  await expect(page).toHaveURL(/onboarding/);
  await page.getByLabel("GSTIN").fill("27ABCDE1234F1Z5");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/dashboard/);
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
