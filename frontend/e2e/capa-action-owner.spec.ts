import { expect, test } from "@playwright/test";
import { apiCaller, PASSWORD, signUp, unique } from "./helpers";
import { acceptInvitation, inviteUser } from "./mail";

test("an action's owner who cannot see CAPAs opens it and reports their action", async ({
  page,
  browser,
}) => {
  await signUp(page);
  const call = apiCaller(page);
  const email = `production-${unique()}@example.com`;
  await inviteUser(page, email, "Production Manager");
  const production = await acceptInvitation(
    browser,
    email,
    "Ravi Kulkarni",
    PASSWORD,
  );

  const me = await call("GET", "/auth/me");
  const members: { items: { userId: string; email: string }[] } = await call(
    "GET",
    "/users?size=100",
  );
  const ravi = members.items.find((member) => member.email === email)!.userId;
  const due = new Date(Date.now() + 14 * 86_400_000).toISOString().slice(0, 10);
  const capa = await call("POST", "/capas", {
    title: "Fixture wear",
    problemStatement: "Holes drift after 2,000 parts",
    ownerId: me.user.id,
    targetDate: due,
    effectivenessRequired: false,
  });
  await call("POST", `/capas/${capa.id}/start-analysis`);
  await page.evaluate(
    (id) =>
      fetch(`/api/v1/capas/${id}/analysis`, {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          rootCause: "No fixture check",
          fiveWhys: [],
          fishbone: {},
        }),
      }),
    capa.id,
  );
  await call("POST", `/capas/${capa.id}/finish-analysis`);
  await call("POST", `/capas/${capa.id}/actions`, {
    actionType: "CORRECTIVE",
    description: "Replace the locating pins",
    ownerId: ravi,
    targetDate: due,
  });
  await call("POST", `/capas/${capa.id}/start-implementation`);

  // The CAPA list is not theirs to see, but this CAPA is, through their action.
  await production.goto(`/capas/${capa.id}`);
  await expect(production.getByText("Replace the locating pins")).toBeVisible();
  await expect(
    production.getByRole("button", { name: "Submit for verification" }),
  ).toHaveCount(0);
  await production.getByRole("button", { name: "Complete" }).click();
  await production
    .getByRole("dialog")
    .getByLabel("What was done")
    .fill("Pins replaced on both fixtures");
  await production
    .getByRole("dialog")
    .getByRole("button", { name: "Save" })
    .click();
  await expect(
    production.getByText(/done: Pins replaced on both fixtures/),
  ).toBeVisible();
  await expect(
    production.getByRole("button", { name: "Complete" }),
  ).toHaveCount(0);
});
