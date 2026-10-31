import { defineConfig, devices } from "@playwright/test";

// Runs against an already started stack: docker compose up --build
export default defineConfig({
  testDir: "./e2e",
  // More than this overloads one machine running the whole stack, and redirects miss their five-second wait.
  workers: 4,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
