import { defineConfig } from "@playwright/test";
import path from "node:path";

export default defineConfig({
  testDir: path.resolve(__dirname, "fixtures"),
  outputDir: path.resolve(__dirname, "test-results"),
  reporter: [
    ["list"],
    [
      "html",
      {
        open: "never",
        outputFolder: path.resolve(__dirname, "playwright-report"),
      },
    ],
  ],
  use: {
    trace: { mode: "retain-on-failure", screenshots: false },
  },
});
