// Local-only mock of the four public health paths, for offline tests of demo-warmup.ps1.
// Never contacts Production. Usage: node mock-health-server.mjs <port> <log.jsonl> <config.json>
// config: { "<path>": { "fail503": N, "delayMs": M, "hangAfter": K } }
//   fail503   - answer 503 to the first N requests on that path, then 200
//   delayMs   - wait M ms before answering
//   hangAfter - after K answered requests, never answer again (the socket stays open)
// Every request arrival is appended to the log as {"t": <ms epoch>, "path": ...}.
import { createServer } from "node:http";
import { appendFileSync, readFileSync } from "node:fs";

const [port, log, cfgFile] = [Number(process.argv[2]), process.argv[3], process.argv[4]];
const cfg = JSON.parse(readFileSync(cfgFile, "utf8"));
const seen = {};
createServer((req, res) => {
  const path = req.url;
  appendFileSync(log, JSON.stringify({ t: Date.now(), path }) + "\n");
  const c = cfg[path] ?? {};
  seen[path] = (seen[path] ?? 0) + 1;
  const n = seen[path];
  if (c.hangAfter !== undefined && n > c.hangAfter) return; // never answer
  const status = c.fail503 !== undefined && n <= c.fail503 ? 503 : 200;
  setTimeout(() => {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(JSON.stringify({ status: status === 200 ? "UP" : "DOWN" }));
  }, c.delayMs ?? 0);
}).listen(port, "127.0.0.1");
