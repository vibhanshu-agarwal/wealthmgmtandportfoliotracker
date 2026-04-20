import { test, expect } from "@playwright/test";
import { installGatewaySessionInitScript } from "./helpers/browser-auth";
import path from "path";


// Create artifacts dir if it doesn't exist
import fs from "fs";

test("debug screenshot ai insights", async ({ page, request }) => {
  await installGatewaySessionInitScript(page, request);
  await page.goto("/ai-insights");
  await page.waitForTimeout(5000);
  const artifactsDir = path.join(process.cwd(), '..', '..', '..', '..', '..', '..', '..', '.gemini', 'antigravity', 'brain', 'a14baa7b-659c-406f-932d-5beb6d91ae98', 'artifacts');
  if (!fs.existsSync(artifactsDir)) {
    fs.mkdirSync(artifactsDir, { recursive: true });
  }
  
  await page.screenshot({ path: path.join(artifactsDir, 'ai-insights-debug.png') });
  console.log("Screenshot saved!");
  
  const html = await page.content();
  console.log("HTML length:", html.length);
  
  const chatInput = await page.locator('[data-testid="chat-input"]').count();
  console.log("Chat input count:", chatInput);
  
  const errorBoundary = await page.locator('text="Application Error"').count();
  console.log("Error boundary count:", errorBoundary);
});
