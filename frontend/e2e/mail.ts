import { expect, type Browser, type Page } from "@playwright/test";

const MAILPIT = process.env.MAILPIT_URL ?? "http://localhost:8025";

/** The newest link to a path, from an email Mailpit caught for this address. */
export async function linkFromEmail(address: string, path: string): Promise<string> {
  let link: string | undefined;
  await expect
    .poll(
      async () => {
        const query = encodeURIComponent(`to:"${address}"`);
        const found = await (await fetch(`${MAILPIT}/api/v1/search?query=${query}`)).json();
        for (const summary of found.messages ?? []) {
          const message = await (await fetch(`${MAILPIT}/api/v1/message/${summary.ID}`)).json();
          const match = (message.Text as string).match(new RegExp(`http\\S*${path}\\?token=\\S+`));
          if (match) {
            link = match[0];
            return true;
          }
        }
        return false;
      },
      { timeout: 10_000 },
    )
    .toBe(true);
  return link!.replace(/^https?:\/\/[^/]+/, "");
}

export async function inviteUser(page: Page, email: string, role: string) {
  await page.goto("/users");
  await page.getByRole("button", { name: "Invite user" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Email").fill(email);
  await dialog.getByRole("checkbox", { name: role }).click();
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(page.getByText(email)).toBeVisible();
}

/** Accepts in a separate browser, as the invited person, and leaves them signed in. */
export async function acceptInvitation(browser: Browser, email: string, name: string, password: string) {
  const page = await (await browser.newContext()).newPage();
  await page.goto(await linkFromEmail(email, "/accept-invitation"));
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Choose a password").fill(password);
  await page.getByRole("button", { name: "Accept invitation" }).click();
  await expect(page).toHaveURL(/dashboard/);
  return page;
}
