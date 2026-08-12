package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import com.wealth.gateway.LoginDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock UserCredentialRepository repository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtSigner jwtSigner;

    private AuthenticationService service() {
        return new AuthenticationService(repository, passwordEncoder, jwtSigner);
    }

    @Test
    void blankEmailFailsBeforeAnyHasherCallOrLookup() {
        var req = new LoginDtos.LoginRequest("  ", "password12345");

        // Mono.error(...).blockOptional() rethrows the error rather than returning an empty
        // Optional (that only happens for an empty-but-successful completion), so assert the
        // thrown type directly.
        assertThatThrownBy(() -> service().authenticate(req).blockOptional())
                .isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(repository, passwordEncoder);
    }

    @Test
    void unknownEmailRunsDummyHashMatchThenFailsUniformly() {
        when(repository.findByEmailIgnoreCase("nobody@x.com")).thenReturn(Optional.empty());
        var req = new LoginDtos.LoginRequest("nobody@x.com", "somepassword");

        try {
            service().authenticate(req).block();
        } catch (InvalidCredentialsException expected) {
            // expected
        }

        verify(passwordEncoder).matches(eq("somepassword"), eq(PasswordHasherConfig.DUMMY_PASSWORD_HASH));
    }

    @Test
    void malformedStoredHashRunsDummyHashMatchThenFailsUniformly() throws Exception {
        // Req 4.6 + timing-oracle fix: a null/blank stored hash must NOT short-circuit past
        // passwordEncoder.matches(...) — it must still run a real match (against the dummy hash
        // as a fallback), the same way the unknown-email branch does, so this outcome isn't
        // distinguishable by timing from the other two 401 paths.
        var row = new UserCredentialRepository.CredentialRow("u1", "a@b.com", "Alice", null, false);
        when(repository.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(row));

        try {
            service().authenticate(new LoginDtos.LoginRequest("a@b.com", "somepassword")).block();
        } catch (InvalidCredentialsException expected) {
            // expected
        }

        verify(passwordEncoder).matches(eq("somepassword"), eq(PasswordHasherConfig.DUMMY_PASSWORD_HASH));
        verify(jwtSigner, never()).signHs256(any(), any(), any(), anyBoolean());
    }

    @Test
    void wrongPasswordFailsUniformlyWithoutMintingAToken() throws Exception {
        var row = new UserCredentialRepository.CredentialRow("u1", "a@b.com", "Alice", "stored-hash", false);
        when(repository.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        try {
            service().authenticate(new LoginDtos.LoginRequest("a@b.com", "wrong")).block();
        } catch (InvalidCredentialsException expected) {
            // expected
        }

        verify(jwtSigner, never()).signHs256(any(), any(), any(), anyBoolean());
    }

    @Test
    void correctPasswordMintsTokenWithReadOnlyFromStoredRow() throws Exception {
        var row = new UserCredentialRepository.CredentialRow("u1", "a@b.com", "Alice", "stored-hash", true);
        when(repository.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("correct", "stored-hash")).thenReturn(true);
        when(jwtSigner.signHs256("u1", "a@b.com", "Alice", true)).thenReturn("jwt-token");

        var result = service().authenticate(new LoginDtos.LoginRequest("a@b.com", "correct")).block();

        assertThat(result).isEqualTo(new LoginResponse("jwt-token", "u1", "a@b.com", "Alice"));
    }

    @Test
    void dataAccessExceptionMapsToCredentialStoreUnavailable() {
        when(repository.findByEmailIgnoreCase("a@b.com"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        try {
            service().authenticate(new LoginDtos.LoginRequest("a@b.com", "whatever12345")).block();
        } catch (CredentialStoreUnavailableException expected) {
            // expected
        }
    }
}
