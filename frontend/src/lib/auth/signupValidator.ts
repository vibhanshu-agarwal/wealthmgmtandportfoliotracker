export type SignupField = "email" | "password" | "name";

export class SignupValidationError extends Error {
  constructor(readonly field: SignupField, message: string) {
    super(message);
    this.name = "SignupValidationError";
  }
}

const MAX_EMAIL_LENGTH = 254;
const MIN_PASSWORD_LENGTH = 12;
const MAX_PASSWORD_BYTES = 72;
const MAX_NAME_LENGTH = 100;
const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export interface ValidatedSignup {
  email: string;
  password: string;
  name: string;
}

/** Mirrors the server's SignupValidator (api-gateway com.wealth.gateway.auth.SignupValidator). */
export function validateSignup(email: string, password: string, name: string): ValidatedSignup {
  if (!email || email.length > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.test(email)) {
    throw new SignupValidationError("email", "Enter a valid email address.");
  }

  const passwordBytes = new TextEncoder().encode(password ?? "").length;
  if (!password || password.length < MIN_PASSWORD_LENGTH || passwordBytes > MAX_PASSWORD_BYTES) {
    throw new SignupValidationError(
      "password",
      `Password must be at least ${MIN_PASSWORD_LENGTH} characters (max 72 bytes).`,
    );
  }

  const trimmedName = (name ?? "").trim();
  if (trimmedName.length === 0) {
    throw new SignupValidationError("name", "Name is required.");
  }
  if (trimmedName.length > MAX_NAME_LENGTH) {
    throw new SignupValidationError("name", "Name is too long (max 100 characters).");
  }

  return { email, password, name: trimmedName };
}
