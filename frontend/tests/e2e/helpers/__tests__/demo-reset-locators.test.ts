// @vitest-environment node
import { readFileSync } from "node:fs";
import path from "node:path";
import ts from "typescript";
import { expect, it } from "vitest";

it("scopes demo-reset alert queries to the conflict notice rather than Next's route announcer", () => {
  const spec = ts.createSourceFile(
    "demo-reset.spec.ts",
    readFileSync(path.resolve(__dirname, "../../demo-reset.spec.ts"), "utf8"),
    ts.ScriptTarget.Latest,
    true,
  );
  const alertQueries: ts.CallExpression[] = [];
  function visit(node: ts.Node): void {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)
      && node.expression.name.text === "getByRole"
      && node.arguments[0] && ts.isStringLiteral(node.arguments[0])
      && node.arguments[0].text === "alert") {
      alertQueries.push(node);
    }
    ts.forEachChild(node, visit);
  }
  visit(spec);
  expect(alertQueries.length).toBeGreaterThan(0);

  for (const query of alertQueries) {
    const member = query.parent;
    const filter = member.parent;
    const options = ts.isCallExpression(filter) ? filter.arguments[0] : undefined;
    const hasConflictText = options && ts.isObjectLiteralExpression(options)
      && options.properties.some((property) => ts.isPropertyAssignment(property)
        && property.name.getText(spec) === "hasText"
        && ts.isStringLiteral(property.initializer)
        && property.initializer.text === "Your portfolio changed elsewhere.");
    // getByRole("alert") alone also selects __next-route-announcer__, including
    // after the conflict disappears. Guard every query, not just the first one.
    expect(ts.isPropertyAccessExpression(member) && member.name.text === "filter"
      && hasConflictText, "each alert query must identify the demo-reset conflict notice").toBe(true);
  }
});
