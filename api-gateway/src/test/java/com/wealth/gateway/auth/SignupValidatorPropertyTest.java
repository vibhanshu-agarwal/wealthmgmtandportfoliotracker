package com.wealth.gateway.auth;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: new-user-signup-profile, Property 1: Signup input validation is exact and
 * side-effect-free. Validates Requirements 1.4, 1.5, 1.6, 1.7, 1.8, 9.2.
 */
class SignupValidatorPropertyTest {

    @Property(tries = 100)
    void acceptsExactlyWhenAllThreeRulesHold(
            @ForAll("validEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("validNames") String name) {
        var result = SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name));

        assertThat(result.email()).isEqualTo(email);
        assertThat(result.password()).isEqualTo(password);
        assertThat(result.name()).isEqualTo(name.trim());
    }

    @Property(tries = 100)
    void rejectsInvalidEmailNamingTheEmailField(
            @ForAll("invalidEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("validNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("email");
    }

    @Property(tries = 100)
    void rejectsInvalidPasswordNamingThePasswordField(
            @ForAll("validEmails") String email,
            @ForAll("invalidPasswords") String password,
            @ForAll("validNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("password");
    }

    @Property(tries = 100)
    void rejectsInvalidNameNamingTheNameField(
            @ForAll("validEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("invalidNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("name");
    }

    @Provide
    Arbitrary<String> validEmails() {
        Arbitrary<String> local = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domainLabel = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        return Combinators.combine(local, domainLabel, domainLabel)
                .as((l, d1, d2) -> l + "@" + d1 + "." + d2);
    }

    @Provide
    Arbitrary<String> invalidEmails() {
        return Arbitraries.of(
                "", "no-at-sign.com", "@nolocal.com", "no-domain@",
                "user@nodot", "user@@double.com", " ", "user @with-space.com",
                "x".repeat(255) + "@toolong.com");
    }

    @Provide
    Arbitrary<String> validPasswords() {
        // 12..72 ASCII chars — guarantees UTF-8 byte length == char length, safely under 72.
        return Arbitraries.strings().withCharRange('!', '~').ofMinLength(12).ofMaxLength(72);
    }

    @Provide
    Arbitrary<String> invalidPasswords() {
        Arbitrary<String> tooShort = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(11);
        // Multibyte passphrase that is <= 72 CHARACTERS but > 72 BYTES (each char is 2+ UTF-8
        // bytes) — this is the specific edge case the byte-length check exists for.
        Arbitrary<String> tooManyBytes = Arbitraries.just("é".repeat(40)); // 40 chars, 80 bytes
        return Arbitraries.oneOf(tooShort, tooManyBytes);
    }

    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(100)
                .map(s -> " " + s + " "); // surrounding whitespace must be trimmed, not rejected
    }

    @Provide
    Arbitrary<String> invalidNames() {
        Arbitrary<String> blank = Arbitraries.of("", "   ", "\t\n");
        Arbitrary<String> tooLong = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(101).ofMaxLength(150);
        return Arbitraries.oneOf(blank, tooLong);
    }
}
