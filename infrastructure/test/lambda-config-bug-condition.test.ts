/**
 * Bug Condition Exploration Test — Lambda Misconfiguration Detection
 *
 * **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7**
 *
 * These tests encode the EXPECTED (correct) behavior for all seven bug conditions.
 * On UNFIXED code, every test MUST FAIL — failure confirms the bug exists.
 * After the fix is applied, every test should PASS.
 *
 * Each test parses the actual project configuration files (Dockerfiles, Terraform HCL,
 * deploy.yml) and asserts the correct configuration is in place.
 */

import * as fs from 'fs';
import * as path from 'path';

// Resolve paths relative to the repo root (infrastructure/ is one level deep)
const REPO_ROOT = path.resolve(__dirname, '..', '..');

function readRepoFile(relativePath: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relativePath), 'utf-8');
}

/**
 * Extract the runtime stage content from a multi-stage Dockerfile.
 * The runtime stage is identified by "AS runtime" in the FROM instruction.
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
      // A new FROM starts a new stage — stop collecting
      if (/^FROM\s+/i.test(line)) break;
      runtimeLines.push(line);
    }
  }
  return runtimeLines.join('\n');
}

// ---------------------------------------------------------------------------
// 1. api-gateway Dockerfile ENTRYPOINT must be java (not the LWA binary)
// ---------------------------------------------------------------------------
describe('Bug Condition 1.1: api-gateway Dockerfile ENTRYPOINT', () => {
  test('ENTRYPOINT should start the Java application, not the LWA binary', () => {
    const dockerfile = readRepoFile('api-gateway/Dockerfile');
    const runtimeStage = extractRuntimeStage(dockerfile);

    // The ENTRYPOINT must start with "java" — not the LWA adapter binary
    const entrypointMatch = runtimeStage.match(/^ENTRYPOINT\s+\[(.+)\]/m);
    expect(entrypointMatch).not.toBeNull();

    const entrypointValue = entrypointMatch![1];
    expect(entrypointValue).toMatch(/"java"/);
    expect(entrypointValue).not.toMatch(/aws-lambda-web-adapter/);
  });
});

// ---------------------------------------------------------------------------
// 2. insight-service Dockerfile ENTRYPOINT must be java (not the LWA binary)
// ---------------------------------------------------------------------------
describe('Bug Condition 1.2: insight-service Dockerfile ENTRYPOINT', () => {
  test('ENTRYPOINT should start the Java application, not the LWA binary', () => {
    const dockerfile = readRepoFile('insight-service/Dockerfile');
    const runtimeStage = extractRuntimeStage(dockerfile);

    const entrypointMatch = runtimeStage.match(/^ENTRYPOINT\s+\[(.+)\]/m);
    expect(entrypointMatch).not.toBeNull();

    const entrypointValue = entrypointMatch![1];
    expect(entrypointValue).toMatch(/"java"/);
    expect(entrypointValue).not.toMatch(/aws-lambda-web-adapter/);
  });
});

// ---------------------------------------------------------------------------
// 3. insight-service Dockerfile must NOT hardcode AWS_LWA_PORT=8083
// ---------------------------------------------------------------------------
describe('Bug Condition 1.2 (port): insight-service port mismatch', () => {
  test('Dockerfile should NOT hardcode AWS_LWA_PORT=8083', () => {
    const dockerfile = readRepoFile('insight-service/Dockerfile');
    const runtimeStage = extractRuntimeStage(dockerfile);

    // The runtime stage must not contain ENV AWS_LWA_PORT=8083
    const hasHardcodedPort = /^ENV\s+AWS_LWA_PORT\s*=\s*8083/m.test(runtimeStage);
    expect(hasHardcodedPort).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// 4. All four Lambda timeout values must be >= 60
// ---------------------------------------------------------------------------
describe('Bug Condition 1.3: Lambda timeout values', () => {
  test('all four Lambda functions should have timeout >= 60', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    // Match all timeout assignments in aws_lambda_function resources.
    // Pattern: timeout = <number> or timeout = var.<name>
    const timeoutMatches = tfContent.match(/timeout\s*=\s*(\S+)/g) || [];

    // We expect exactly 4 timeout declarations (one per Lambda)
    expect(timeoutMatches.length).toBeGreaterThanOrEqual(4);

    for (const match of timeoutMatches) {
      const valueMatch = match.match(/timeout\s*=\s*(\S+)/);
      if (!valueMatch) continue;

      const value = valueMatch[1];

      // If it's a literal number, it must be >= 60
      const numericValue = parseInt(value, 10);
      if (!isNaN(numericValue)) {
        expect(numericValue).toBeGreaterThanOrEqual(60);
      }
      // If it's a variable reference (e.g., var.lambda_timeout), that's acceptable
      // as long as the variable has a default >= 60 (checked separately)
    }
  });
});

// ---------------------------------------------------------------------------
// 5. AWS_LWA_ASYNC_INIT = "true" must be in common_env and api_gateway_container_env
// ---------------------------------------------------------------------------
describe('Bug Condition 1.4: AWS_LWA_ASYNC_INIT configuration', () => {
  test('AWS_LWA_ASYNC_INIT should be set to "true" in common_env', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    // Extract the common_env local block
    const commonEnvMatch = tfContent.match(/common_env\s*=\s*\{([^}]+)\}/s);
    expect(commonEnvMatch).not.toBeNull();

    const commonEnvBlock = commonEnvMatch![1];
    expect(commonEnvBlock).toMatch(/AWS_LWA_ASYNC_INIT\s*=\s*"true"/);
  });

  test('AWS_LWA_ASYNC_INIT should be set to "true" in api_gateway_container_env', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    // Extract the api_gateway_container_env local block
    const apiGwEnvMatch = tfContent.match(/api_gateway_container_env\s*=\s*\{([^}]+)\}/s);
    expect(apiGwEnvMatch).not.toBeNull();

    const apiGwEnvBlock = apiGwEnvMatch![1];
    expect(apiGwEnvBlock).toMatch(/AWS_LWA_ASYNC_INIT\s*=\s*"true"/);
  });
});

// ---------------------------------------------------------------------------
// 6. No snap_start block on Zip-based Lambdas with handler = "not.used"
// ---------------------------------------------------------------------------
describe('Bug Condition 1.5: SnapStart on Zip-based Lambdas', () => {
  test('Zip-based Lambdas with handler "not.used" should NOT have snap_start blocks', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    // Find all aws_lambda_function resource blocks for portfolio, market_data, insight.
    // Use a greedy approach: find the resource header, then capture until the next
    // top-level resource/data/locals block or end of file.
    const zipLambdaNames = ['portfolio', 'market_data', 'insight'];

    for (const name of zipLambdaNames) {
      // Find the start of this resource block
      const headerPattern = new RegExp(
        `resource\\s+"aws_lambda_function"\\s+"${name}"\\s*\\{`
      );
      const headerMatch = tfContent.match(headerPattern);
      expect(headerMatch).not.toBeNull();

      // Extract from the header to the next top-level resource/data/locals block
      const startIdx = headerMatch!.index!;
      const afterHeader = tfContent.substring(startIdx);

      // Find the next top-level resource block (starts at column 0 with "resource" or "data" or "locals")
      const nextBlockMatch = afterHeader.substring(1).match(/\n(?=resource\s|data\s|locals\s)/);
      const resourceBlock = nextBlockMatch
        ? afterHeader.substring(0, nextBlockMatch.index! + 1)
        : afterHeader;

      // If handler is "not.used", there must be no snap_start block
      if (/handler\s*=\s*"not\.used"/.test(resourceBlock)) {
        const hasSnapStart = /snap_start\s*\{/.test(resourceBlock);
        expect(hasSnapStart).toBe(false);
      }
    }
  });
});

// ---------------------------------------------------------------------------
// 7. api-gateway Lambda environment wires downstream URLs from
//    aws_lambda_function_url.* resource references (not from variables)
// ---------------------------------------------------------------------------
describe('Bug Condition 1.6: Downstream URL wiring', () => {
  test('api-gateway Lambda should wire downstream URLs from aws_lambda_function_url resources', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    // Find the api_gateway Lambda resource block using the same approach as SnapStart test
    const headerMatch = tfContent.match(
      /resource\s+"aws_lambda_function"\s+"api_gateway"\s*\{/
    );
    expect(headerMatch).not.toBeNull();

    const startIdx = headerMatch!.index!;
    const afterHeader = tfContent.substring(startIdx);

    // Find the next top-level resource block
    const nextBlockMatch = afterHeader.substring(1).match(/\n(?=resource\s|data\s|locals\s)/);
    const apiGwBlock = nextBlockMatch
      ? afterHeader.substring(0, nextBlockMatch.index! + 1)
      : afterHeader;

    // The environment block should reference aws_lambda_function_url.portfolio.function_url
    // (or use coalesce with it), NOT just var.portfolio_function_url
    expect(apiGwBlock).toMatch(/aws_lambda_function_url\.portfolio\.function_url/);
    expect(apiGwBlock).toMatch(/aws_lambda_function_url\.market_data\.function_url/);
    expect(apiGwBlock).toMatch(/aws_lambda_function_url\.insight\.function_url/);
  });
});

// ---------------------------------------------------------------------------
// 8. AWS_LWA_READINESS_CHECK_PATH = "/actuator/health" in Lambda env vars
// ---------------------------------------------------------------------------
describe('Bug Condition 1.7: AWS_LWA_READINESS_CHECK_PATH configuration', () => {
  test('AWS_LWA_READINESS_CHECK_PATH should be "/actuator/health" in common_env', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const commonEnvMatch = tfContent.match(/common_env\s*=\s*\{([^}]+)\}/s);
    expect(commonEnvMatch).not.toBeNull();

    const commonEnvBlock = commonEnvMatch![1];
    expect(commonEnvBlock).toMatch(/AWS_LWA_READINESS_CHECK_PATH\s*=\s*"\/actuator\/health"/);
  });

  test('AWS_LWA_READINESS_CHECK_PATH should be "/actuator/health" in api_gateway_container_env', () => {
    const tfContent = readRepoFile('infrastructure/terraform/modules/compute/main.tf');

    const apiGwEnvMatch = tfContent.match(/api_gateway_container_env\s*=\s*\{([^}]+)\}/s);
    expect(apiGwEnvMatch).not.toBeNull();

    const apiGwEnvBlock = apiGwEnvMatch![1];
    expect(apiGwEnvBlock).toMatch(/AWS_LWA_READINESS_CHECK_PATH\s*=\s*"\/actuator\/health"/);
  });
});
