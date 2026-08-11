package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock UserCredentialRepository repository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtSigner jwtSigner;
    @Mock TransactionTemplate transactionTemplate;

    private SignupService service() {
        return new SignupService(repository, passwordEncoder, jwtSigner, transactionTemplate);
    }

    /** Makes the mocked TransactionTemplate actually invoke the callback (no real transaction). */
    private void stubTransactionToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
    }

    @Test
    void invalidRequestFailsBeforeAnyRepositoryCall() {
        var req = new SignupDtos.SignupRequest("not-an-email", "whatever12345", "Name");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(repository, transactionTemplate);
    }

    @Test
    void validRequestHashesPasswordAndMintsTokenWithReadOnlyFalse() throws Exception {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode("password12345")).thenReturn("hashed");
        when(jwtSigner.signHs256(any(), eq("a@b.com"), eq("Alice"), eq(false))).thenReturn("jwt");

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "  Alice  ");
        var result = service().provision(req).block();

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(result.email()).isEqualTo("a@b.com");
        assertThat(result.name()).isEqualTo("Alice"); // trimmed
        verify(repository).insertUser(any(), eq("a@b.com"), eq("Alice"));
        verify(repository).insertCredential(any(), eq("a@b.com"), eq("hashed"));
    }

    @Test
    void duplicateKeyOnInsertMapsToDuplicateEmailException() {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        doThrow(new DuplicateKeyException("dup")).when(repository).insertUser(any(), any(), any());

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "Alice");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(DuplicateEmailException.class);
    }
}
