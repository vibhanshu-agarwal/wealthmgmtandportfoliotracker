// Local stand-in for the frontend origin, for the Production stop-gate control.
//
// Serves a Next-shaped /login page naming whichever build id it is told to serve, so the
// control can exercise a REAL mismatch — fetch, extract, compare — without contacting
// Production. Binds 127.0.0.1 on an ephemeral port and prints it as JSON so the runner
// never needs a fixed port.
import http from "node:http";

const servedBuildId = process.env.CONTROL_SERVED_BUILD_ID;
if (!servedBuildId) {
  process.stderr.write("CONTROL_SERVED_BUILD_ID is required\n");
  process.exit(2);
}

// The flight payload's root row, with the escaped quoting the served HTML really carries,
// so the extraction under test sees the same shape it sees in production.
const row = '0:{\\"b\\":\\"' + servedBuildId + '\\",\\"p\\":\\"\\"}';
const html =
  "<html><body>" +
  '<script src="/_next/static/' + servedBuildId + '/main.js"></script>' +
  '<script>self.__next_f.push([1,"' + row + '"])</script>' +
  "</body></html>";

const server = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(html);
});

server.listen(0, "127.0.0.1", () => {
  process.stdout.write(JSON.stringify({ port: server.address().port }) + "\n");
});
