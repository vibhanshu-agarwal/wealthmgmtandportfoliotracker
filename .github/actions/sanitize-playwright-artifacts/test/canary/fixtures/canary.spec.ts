import { expect, test } from "@playwright/test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import {
  localServerUrl,
  startLocalServer,
  stopLocalServer,
} from "./local-server";

const SENTINEL = "TestPassword123!";

test.beforeAll(async () => {
  await startLocalServer();
});

test.afterAll(async () => {
  await stopLocalServer();
});

test("captures dummy sentinel via DOM fill and APIRequestContext", async ({
  page,
  request,
}) => {
  const htmlPath = path.join(os.tmpdir(), "canary-login.html");
  fs.writeFileSync(
    htmlPath,
    "<!doctype html><html><body><input type=\"password\" id=\"pw\"></body></html>\n",
  );
  await page.goto(pathToFileURL(htmlPath).href);
  await page.locator("#pw").fill(SENTINEL);
  await request.post(localServerUrl(), {
    data: { password: SENTINEL },
  });
  expect(false).toBe(true);
});
