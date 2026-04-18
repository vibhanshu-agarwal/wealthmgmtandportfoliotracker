/**
 * Preservation Property Tests — Unchanged Behaviors After Fix
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**
 *
 * These tests capture baseline behavior for non-buggy configurations that MUST
 * remain unchanged after the fix is applied. They follow the observation-first
 * methodology: observe behavior on UNFIXED code, then encode it as tests.
 *
 * All tests MUST PASS on the current (unfixed) code.
 * All tests MUST STILL PASS after the fix is applied.
 */

import * as fs from 'fs';
import * as path from 'path';

const REPO_ROOT = path.resolve(__dirname, '..', '..');

function readRepoFile(relativePath: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relativePath), 'utf-8');
}

/**
 * Extract the runtime stage content from a multi-stage Dockerfile.
 */
function extractRuntimeStage(dockerfile: string): string {
  const lines = dockerfile.split('\n');
  let inRuntime = false;
  const runtimeLines: string[] = [];

  for (const line of lines) {
    if (/^FROM\s+.*\s+AS\s+runtime/i.test(line)) {
      inRuntime = true;
      runtimeLines.push(line);
      continue;
    }
    if (inRuntime) {
      if (/^FROM\s+/i.test(line)) break;
      runtimeLines.push(line);
    }
  }
  return runtimeLines.join('\n');
}

/**
 * Extract a top-level Terraform resource block by type and name.
 */
function extractTfResourceBlock(tfContent: string, resourceType: string, resourceName: string): string {
  const headerPattern = new RegExp(
    `resource\\s+"${resourceType.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"\\s+"${resourceName}"\\s*\\{`
  );
  const headerMatch = tfContent.match(headerPattern);
  if (!headerMatch || headerMatch.index === undefined) return '';

  const startIdx = headerMatch.index;
  const afterHeader = tfContent.substring(startIdx);

  // Find the next top-level block (resource, data, locals, output at column 0)
  const nextBlockMatch = afterHeader.substring(1).match(/\n(?=resource\s|data\s|locals\s|output\s)/);
  return nextBlockMatch
    ? afterHeader.substring(0, nextBlockMatch.index! + 1)
    : afterHeader;
}

// ---------------------------------------------------------------------------
// 1. portfolio-service Dockerfile ENTRYPOINT must NOT be modified by the fix
// ---------------------------------------------------------------------------
describe('Preservation 3.2: portfolio-service Dockerfile ENTRYPOINT', () => {
  test('ENTRYPOINT is ["java", "-jar", "/app/app.jar"]', () => {
    const dockerfile = readRepoFile('portfolio-service/Dockerfile');
    const runtimeStage = extractRuntimeStage(dockerfile);

    const entrypointMatch = runtimeStage.match(/^ENTRYPOINT\s+\[(.+)\]/m);
    expect(entrypointMatch).not.toBeNull();

    const entrypointValue = entrypointMatch![1];
    // Must be exactly: "java", "-jar", "/app/app.jar"
    expect(entrypointValue).toContain('"java"');
    expect(entrypointValue).toContain('"-jar"');
    expect(entrypointValue).toContain('"/app/app.jar"');
    expect(entrypointValue).not.toContain('aws-lambda-web-adapter');
  });
});

// ---------------------------------------------------------------------------
// 2. market-data-service Dockerfile ENTRYPOINT must NOT be modified by the fix
// ---------------------------------------------------------------------------
describe('Preservation 3.2: market-data-service Dockerfile ENTRYPOINT', () => {
  test('ENTRYPOINT is ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]', () => {
    const dockerfile = readRepoFile('market-data-service/Dockerfile');
    const runtimeStage = extractRuntimeStage(dockerfile);

    const entrypointMatch = runtimeStage.match(/^ENTRYPOINT\s+\[(.+)\]/m);
    expect(entrypointMatch).not.toBeNull();

    const entrypointValue = entrypointMatch![1];
    // Must be exactly: "java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"
    expect(entrypointValue).toContain('"java"');
    expect(entrypointValue).toContain('"-Dspring.aot.enabled=true"');
    expect(entrypointValue).toContain('"-jar"');
    expect(entrypointValue).toContain('"/app/app.jar"');
    expect(entrypointValue).not.toContain('aws-lambda-web-adapter');
  });
});

// ---------------------------------------------------------------------------
// 3. Neither portfolio-service nor market-data-service use LWA as ENTRYPOINT
// ---------------------------------------------------------------------------
describe('Preservation 3.2: No LWA ENTRYPOINT in portfolio/market-data Dockerfiles', () => {
  const services = ['portfolio-service', 'market-data-service'];

  test.each(services)('%s Dockerfile does NOT use /opt/extensions/aws-lambda-web-adapter as ENTRYPOINT', (service) => {
    const dockerfile = readRepoFile(`${service}/Dockerfile`);
    const runtimeStage = extractRuntimeStage(dockerfile);

    const entrypointMatch = runtimeStage.match(/^ENTRYPOINT\s+\[(.+)\]/m);
    expect(entrypointMatch).not.toBeNull();

    const entrypointValue = entrypointMatch![1];
    expect(entrypointValue).not.toMatch(/aws-lambda-web-adapter/);
  });
});

// ---------------------------------------------------------------------------
// 4. IAM roles — four roles with lambda.amazonaws.com assume role
// ---------------------------------------------------------------------------
describe('Preservation 3.6: IAM execution roles', () => {
  const expectedRoles = ['api_gateway', 'portfolio', 'market_data', 'insight'];

  test('lambda_assume_role policy document uses lambda.amazonaws.com', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const assumeRoleMatch = tfContent.match(
      /data\s+"aws_iam_policy_document"\s+"lambda_assume_role"\s*\{([\s\S]*?)\n\}/
    );
    expect(assumeRoleMatch).not.toBeNull();

    const policyBlock = assumeRoleMatch![1];
    expect(policyBlock).toMatch(/identifiers\s*=\s*\["lambda\.amazonaws\.com"\]/);
  });

  test.each(expectedRoles)('IAM role "%s" exists and uses lambda_assume_role policy', (roleName) => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const roleBlock = extractTfResourceBlock(tfContent, 'aws_iam_role', roleName);
    expect(roleBlock.length).toBeGreaterThan(0);
    expect(roleBlock).toMatch(/assume_role_policy\s*=\s*data\.aws_iam_policy_document\.lambda_assume_role\.json/);
  });
});

// ---------------------------------------------------------------------------
// 5. Function URL resources — four with authorization_type = "NONE"
// ---------------------------------------------------------------------------
describe('Preservation 3.6: Function URL resources', () => {
  const expectedFunctionUrls = ['api_gateway', 'portfolio', 'market_data', 'insight'];

  test.each(expectedFunctionUrls)('aws_lambda_function_url.%s has authorization_type = "NONE"', (name) => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const urlBlock = extractTfResourceBlock(tfContent, 'aws_lambda_function_url', name);
    expect(urlBlock.length).toBeGreaterThan(0);
    expect(urlBlock).toMatch(/authorization_type\s*=\s*"NONE"/);
  });
});

// ---------------------------------------------------------------------------
// 6. Lambda alias resources — four with name = "live"
// ---------------------------------------------------------------------------
describe('Preservation 3.6: Lambda alias resources', () => {
  const expectedAliases = [
    'api_gateway_live',
    'portfolio_live',
    'market_data_live',
    'insight_live',
  ];

  test.each(expectedAliases)('aws_lambda_alias.%s has name = "live"', (aliasName) => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const aliasBlock = extractTfResourceBlock(tfContent, 'aws_lambda_alias', aliasName);
    expect(aliasBlock.length).toBeGreaterThan(0);
    expect(aliasBlock).toMatch(/name\s*=\s*"live"/);
  });
});

// ---------------------------------------------------------------------------
// 7. deploy.yml retains both deploy-frontend and deploy-backend jobs
// ---------------------------------------------------------------------------
describe('Preservation 3.4: deploy.yml workflow structure', () => {
  test('deploy.yml contains deploy-frontend job', () => {
    const deployYml = readRepoFile('.github/workflows/deploy.yml');
    expect(deployYml).toMatch(/deploy-frontend:/);
  });

  test('deploy.yml contains deploy-backend job', () => {
    const deployYml = readRepoFile('.github/workflows/deploy.yml');
    expect(deployYml).toMatch(/deploy-backend:/);
  });
});

// ---------------------------------------------------------------------------
// 8. VPC config dynamic blocks remain conditional on local.attach_lambda_vpc
// ---------------------------------------------------------------------------
describe('Preservation 3.6: VPC config dynamic blocks', () => {
  const lambdaFunctions = ['api_gateway', 'portfolio', 'market_data', 'insight'];

  test.each(lambdaFunctions)('aws_lambda_function.%s has vpc_config conditional on local.attach_lambda_vpc', (name) => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const funcBlock = extractTfResourceBlock(tfContent, 'aws_lambda_function', name);
    expect(funcBlock.length).toBeGreaterThan(0);

    // Must contain a dynamic "vpc_config" block with for_each referencing local.attach_lambda_vpc
    expect(funcBlock).toMatch(/dynamic\s+"vpc_config"/);
    expect(funcBlock).toMatch(/for_each\s*=\s*local\.attach_lambda_vpc\s*\?\s*\[1\]\s*:\s*\[\]/);
  });
});

// ---------------------------------------------------------------------------
// 9. TF_VAR override variables still exist in modules/compute/variables.tf
// ---------------------------------------------------------------------------
describe('Preservation 3.7: TF_VAR override mechanism preserved', () => {
  const overrideVars = [
    'portfolio_function_url',
    'market_data_function_url',
    'insight_function_url',
  ];

  test.each(overrideVars)('variable "%s" is declared in modules/compute/variables.tf', (varName) => {
    const varsContent = readRepoFile('infrastructure/terraform/modules/compute/variables.tf');

    const varPattern = new RegExp(`variable\\s+"${varName}"\\s*\\{`);
    expect(varsContent).toMatch(varPattern);
  });
});
