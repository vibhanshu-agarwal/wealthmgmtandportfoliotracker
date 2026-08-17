import http from "node:http";
import type { AddressInfo } from "node:net";

let server: http.Server | undefined;
let serverUrl = "";

export function localServerUrl(): string {
  return serverUrl;
}

export async function startLocalServer(): Promise<string> {
  server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
    req.on("end", () => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, bytes: Buffer.concat(chunks).length }));
    });
  });
  await new Promise<void>((resolve, reject) => {
    server!.once("error", reject);
    server!.listen(0, "127.0.0.1", () => resolve());
  });
  const { port } = server.address() as AddressInfo;
  serverUrl = `http://127.0.0.1:${port}`;
  return serverUrl;
}

export async function stopLocalServer(): Promise<void> {
  if (!server) return;
  await new Promise<void>((resolve, reject) => {
    server!.close((err) => (err ? reject(err) : resolve()));
  });
  server = undefined;
  serverUrl = "";
}
