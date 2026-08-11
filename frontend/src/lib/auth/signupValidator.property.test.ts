import { describe, it, expect } from "vitest";
import fc from "fast-check";
import { validateSignup, SignupValidationError } from "./signupValidator";

// Feature: new-user-signup-profile, Property 1 (frontend companion): client validator mirrors
// SignupValidator. Validates Requirements 5.1, 5.2, 5.3 (mirrors 1.4-1.8, 9.2).
describe("validateSignup — property: mirrors the server SignupValidator", () => {
  const validEmail = fc
    .tuple(fc.stringMatching(/^[a-z]{1,20}$/), fc.stringMatching(/^[a-z]{1,10}$/), fc.stringMatching(/^[a-z]{1,10}$/))
    .map(([local, d1, d2]) => `${local}@${d1}.${d2}`);
  const validPassword = fc.string({ minLength: 12, maxLength: 72, unit: "grapheme-ascii" });
  const validName = fc.string({ minLength: 1, maxLength: 100, unit: "grapheme-ascii" }).map((s) => ` ${s} `);

  it("accepts any (email, password, name) satisfying all three rules and trims the name", () => {
    fc.assert(
      fc.property(validEmail, validPassword, validName, (email, password, name) => {
        const result = validateSignup(email, password, name);
        expect(result.email).toBe(email);
        expect(result.password).toBe(password);
        expect(result.name).toBe(name.trim());
      }),
      { numRuns: 100 },
    );
  });

  it("rejects a password shorter than 12 characters, naming the password field", () => {
    fc.assert(
      fc.property(validEmail, fc.string({ maxLength: 11 }), validName, (email, password, name) => {
        expect(() => validateSignup(email, password, name)).toThrowError(SignupValidationError);
        try {
          validateSignup(email, password, name);
        } catch (e) {
          expect((e as SignupValidationError).field).toBe("password");
        }
      }),
      { numRuns: 100 },
    );
  });

  it("rejects a password whose UTF-8 byte length exceeds 72 even under 72 characters", () => {
    // 'é' is 2 UTF-8 bytes; 40 repeats = 40 chars but 80 bytes.
    const multibytePassword = "é".repeat(40);
    expect(() => validateSignup("a@b.com", multibytePassword, "Name")).toThrowError(SignupValidationError);
    try {
      validateSignup("a@b.com", multibytePassword, "Name");
    } catch (e) {
      expect((e as SignupValidationError).field).toBe("password");
    }
  });

  it("rejects a blank or overlong name, naming the name field", () => {
    fc.assert(
      fc.property(validEmail, validPassword, fc.constantFrom("", "   ", "x".repeat(101)), (email, password, name) => {
        expect(() => validateSignup(email, password, name)).toThrowError(SignupValidationError);
        try {
          validateSignup(email, password, name);
        } catch (e) {
          expect((e as SignupValidationError).field).toBe("name");
        }
      }),
      { numRuns: 100 },
    );
  });
});
