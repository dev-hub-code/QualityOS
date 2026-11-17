import { expect, test } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

test("a new notification shows on the bell at once, without reloading", async ({ page, browser }) => {
  await signUp(page);
  const managerEmail = `manager-${unique()}@example.com`;
  await inviteUser(page, managerEmail, "Quality Manager");
  const manager = await acceptInvitation(browser, managerEmail, "Meera Iyer", PASSWORD);
  await manager.goto("/dashboard");
  const bell = manager.getByRole("link", { name: /^Notifications/ }).first();
  await expect(bell).toHaveAccessibleName("Notifications");

  // Well inside the five-minute poll: only the live stream can bring it this soon.
  const raised = await apiCaller(page)("POST", "/ncrs", {
    title: "Burr on flange",
    description: "Found at final check",
    severity: "MAJOR",
  });
  expect(raised.ncrNumber).toMatch(/^NCR-/);
  await expect(bell).toHaveAccessibleName("Notifications, 1 unread", { timeout: 10_000 });

  // Reading it elsewhere clears the bell here too.
  const status = await manager.evaluate(async () =>
    (await fetch("/api/v1/notifications/read-all", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" })).status,
  );
  expect(status).toBe(204);
  await expect(bell).toHaveAccessibleName("Notifications", { timeout: 10_000 });
});
