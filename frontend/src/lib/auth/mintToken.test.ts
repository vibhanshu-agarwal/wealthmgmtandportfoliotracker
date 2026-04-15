// @vitest-environment node
import { describe, it, expect, beforeAll } from "vitest";
import { vi } from "vitest";
import fc from "fast-check";
import { jwtVerify } from "jose";
import { mintToken } from "./mintToken";

// Mock server-only since it's a server-only guard
vi.mock("server-only", () => ({}));

const TEST_SECRET = "test-secret-for-property-tests-min-32-chars!";

describe("mintToken — Property 1: Mint-then-verify round trip", () => {
  /**
   * **Validates: Requirements 1.1, 1.2, 5.2, 7.1**
   *
   * For any valid TokenUser, minting a JWT with mintToken and then verifying
   * it with jwtVerify using the same AUTH_JWT_SECRET SHALL succeed without error.
   */

  beforeAll(() => {
    vi.stubEnv("AUTH_JWT_SECRET", TEST_SECRET);
  });

  const tokenUserArb = fc.record({
    id: fc.uuid(),
    email: fc.emailAddress(),
    name: fc.string({ minLength: 1 }),
  });

  it(
    "mint-then-verify round trip succeeds for any valid TokenUser",
    async () => {
      await fc.assert(
        fc.asyncProperty(tokenUserArb, async (user) => {
          const jwt = await mintToken(user);

          const secret = new TextEncoder().encode(TEST_SECRET);
          const { payload, protectedHeader } = await jwtVerify(jwt, secret);

          // Verification succeeded — no error thrown
          expect(payload).toBeDefined();
          expect(protectedHeader).toBeDefined();
        }),
        { numRuns: 100 },
      );
    },
  );
});

describe("mintToken — Property 2: Claim preservation", () => {
  /**
   * **Validates: Requirements 1.4, 7.2, 7.3**
   *
   * For any valid TokenUser, the JWT produced by mintToken SHALL contain
   * a `sub` claim equal to `user.id`, an `email` claim equal to `user.email`,
   * and a `name` claim equal to `user.name`.
   */

  beforeAll(() => {
    vi.stubEnv("AUTH_JWT_SECRET", TEST_SECRET);
  });

  const tokenUserArb = fc.record({
    id: fc.uuid(),
    email: fc.emailAddress(),
    name: fc.string({ minLength: 1 }),
  });

  it(
    "minted JWT claims match the input TokenUser fields",
    async () => {
      await fc.assert(
        fc.asyncProperty(tokenUserArb, async (user) => {
          const jwt = await mintToken(user);

          const secret = new TextEncoder().encode(TEST_SECRET);
          const { payload } = await jwtVerify(jwt, secret);

          expect(payload.sub).toBe(user.id);
          expect(payload.email).toBe(user.email);
          expect(payload.name).toBe(user.name);
        }),
        { numRuns: 100 },
      );
    },
  );
});

describe("mintToken — Property 3: Expiry invariant", () => {
  /**
   * **Validates: Requirements 1.5, 1.6, 7.4**
   *
   * For any valid TokenUser, the JWT produced by mintToken SHALL have an `exp`
   * claim exactly 3600 seconds after the `iat` claim.
   */

  beforeAll(() => {
    vi.stubEnv("AUTH_JWT_SECRET", TEST_SECRET);
  });

  const tokenUserArb = fc.record({
    id: fc.uuid(),
    email: fc.emailAddress(),
    name: fc.string({ minLength: 1 }),
  });

  it(
    "exp - iat === 3600 for any valid TokenUser",
    async () => {
      await fc.assert(
        fc.asyncProperty(tokenUserArb, async (user) => {
          const jwt = await mintToken(user);

          const secret = new TextEncoder().encode(TEST_SECRET);
          const { payload } = await jwtVerify(jwt, secret);

          expect(payload.iat).toBeDefined();
          expect(payload.exp).toBeDefined();
          expect(payload.exp! - payload.iat!).toBe(3600);
        }),
        { numRuns: 100 },
      );
    },
  );
});

describe("mintToken — Property 4: Algorithm header invariant", () => {
  /**
   * **Validates: Requirements 1.7, 7.5**
   *
   * For any valid TokenUser, the JWT produced by mintToken SHALL have a
   * protected header `alg` field equal to "HS256".
   */

  beforeAll(() => {
    vi.stubEnv("AUTH_JWT_SECRET", TEST_SECRET);
  });

  const tokenUserArb = fc.record({
    id: fc.uuid(),
    email: fc.emailAddress(),
    name: fc.string({ minLength: 1 }),
  });

  it(
    "protected header alg is HS256 for any valid TokenUser",
    async () => {
      await fc.assert(
        fc.asyncProperty(tokenUserArb, async (user) => {
          const jwt = await mintToken(user);

          const secret = new TextEncoder().encode(TEST_SECRET);
          const { protectedHeader } = await jwtVerify(jwt, secret);

          expect(protectedHeader.alg).toBe("HS256");
        }),
        { numRuns: 100 },
      );
    },
  );
});

describe("mintToken — Unit: Secret fallback behavior", () => {
  /**
   * **Validates: Requirements 1.2, 1.3, 5.1**
   *
   * Unit tests verifying:
   * - mintToken falls back to BETTER_AUTH_SECRET when AUTH_JWT_SECRET is unset
   * - mintToken reads the secret at invocation time (not module load time)
   */

  it("falls back to BETTER_AUTH_SECRET when AUTH_JWT_SECRET is unset", async () => {
    const fallbackSecret = "fallback-better-auth-secret-min-32-chars!!";

    vi.stubEnv("AUTH_JWT_SECRET", "");
    // Ensure AUTH_JWT_SECRET is truly absent (empty string is falsy, but ?? checks for nullish)
    delete process.env.AUTH_JWT_SECRET;
    vi.stubEnv("BETTER_AUTH_SECRET", fallbackSecret);

    const user = { id: "user-1", email: "test@example.com", name: "Test User" };
    const jwt = await mintToken(user);

    // Verify the token with BETTER_AUTH_SECRET — should succeed
    const secret = new TextEncoder().encode(fallbackSecret);
    const { payload } = await jwtVerify(jwt, secret);

    expect(payload.sub).toBe(user.id);
    expect(payload.email).toBe(user.email);
    expect(payload.name).toBe(user.name);
  });

  it("reads the secret at invocation time, not module load time", async () => {
    const secret1 = "first-secret-for-invocation-time-test-32!";
    const secret2 = "second-secret-for-invocation-time-test-3!";

    const user = { id: "user-2", email: "dynamic@example.com", name: "Dynamic User" };

    // Mint with first secret
    vi.stubEnv("AUTH_JWT_SECRET", secret1);
    const jwt1 = await mintToken(user);

    // Change the env var and mint again
    vi.stubEnv("AUTH_JWT_SECRET", secret2);
    const jwt2 = await mintToken(user);

    // The two JWTs must be different (signed with different secrets)
    expect(jwt1).not.toBe(jwt2);

    // Verify each JWT with its respective secret
    const { payload: payload1 } = await jwtVerify(
      jwt1,
      new TextEncoder().encode(secret1),
    );
    expect(payload1.sub).toBe(user.id);

    const { payload: payload2 } = await jwtVerify(
      jwt2,
      new TextEncoder().encode(secret2),
    );
    expect(payload2.sub).toBe(user.id);

    // Cross-verification should fail: jwt1 verified with secret2 should throw
    await expect(
      jwtVerify(jwt1, new TextEncoder().encode(secret2)),
    ).rejects.toThrow();
  });
});
