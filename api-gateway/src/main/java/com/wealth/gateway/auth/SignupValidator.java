package com.wealth.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Pure validation for signup requests — no I/O, no Spring dependency (Req 1.4-1.8, 9.2).
 *
 * Email_Format_Rule: local@domain, non-empty local part, domain with >= 1 dot.
 * Password_Policy: >= 12 characters AND UTF-8 byte length <= 72 (BCrypt's input limit —
 * checked on byte length, NOT character count, since a multibyte passphrase can exceed
 * 72 bytes under 72 characters).
 */
public final class SignupValidator {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MAX_NAME_LENGTH = 100;

    // local part: 1+ non-@ non-whitespace chars. domain: 1+ labels separated by dots, each
    // label alphanumeric/hyphen, at least one dot required (Email_Format_Rule).
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private SignupValidator() {}

    public static SignupDtos.ValidatedSignup validate(SignupDtos.SignupRequest req) {
        if (req == null || req.email() == null) {
            throw new ValidationException("email", "email is required");
        }
        if (req.password() == null) {
            throw new ValidationException("password", "password is required");
        }
        if (req.name() == null) {
            throw new ValidationException("name", "name is required");
        }

        String email = req.email();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ValidationException("email", "email is invalid");
        }

        String password = req.password();
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < MIN_PASSWORD_LENGTH || passwordBytes > MAX_PASSWORD_BYTES) {
            throw new ValidationException("password", "password does not meet policy");
        }

        String trimmedName = req.name().trim();
        if (trimmedName.isEmpty()) {
            throw new ValidationException("name", "name is required");
        }
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("name", "name is too long");
        }

        return new SignupDtos.ValidatedSignup(email, password, trimmedName);
    }
}
