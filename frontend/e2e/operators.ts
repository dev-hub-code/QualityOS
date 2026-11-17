import { execFileSync } from "node:child_process";
import { expect, type Page } from "@playwright/test";
import { PASSWORD } from "./helpers";
import { sealedForSql, TEST_SECRET, totp } from "./second-factor";

/**
 * Being a platform administrator can only be granted in the database, on purpose. The operator gets an
 * authenticator app already set up, unless a test wants to watch that happen.
 */
export function makePlatformAdmin(email: string, recentlyConfirmed = true, enrolled = true) {
  const statements = [
    `INSERT INTO platform_admins (user_id, reauthenticated_until) SELECT id, ${recentlyConfirmed ? "now() + INTERVAL '1 hour'" : "NULL"} FROM users WHERE email = '${email}'`,
  ];
  if (enrolled) {
    statements.push(
      `INSERT INTO platform_second_factors (user_id, secret, confirmed_at) SELECT id, ${sealedForSql(TEST_SECRET)}, now() FROM users WHERE email = '${email}'`,
    );
  }
  execFileSync("docker", [
    "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "qualityos", "-c", statements.join("; "),
  ]);
}

/** The second step of an operator's sign-in, with the code the test's authenticator would show. */
export async function enterSecondFactor(page: Page, secret = TEST_SECRET) {
  await page.getByLabel("Six-digit code").fill(totp(secret));
  await page.getByRole("button", { name: "Continue" }).click();
}

/** Signs an operator in from scratch: password, then authenticator code, landing in the Control Center. */
export async function signInOperator(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await enterSecondFactor(page);
  await expect(page).toHaveURL(/\/platform\/dashboard/);
}
