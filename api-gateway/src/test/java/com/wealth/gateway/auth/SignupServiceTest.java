package com.wealth.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.wealth.gateway.JwtSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
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

    /** Captured by stubTransactionToRunCallback() so failure-path tests can verify
     * setRollbackOnly() was actually invoked on it — not just checked by code inspection. */
    private TransactionStatus transactionStatus;

    private SignupService service() {
        return new SignupService(repository, passwordEncoder, jwtSigner, transactionTemplate);
    }

    /** Makes the mocked TransactionTemplate actually invoke the callback (no real transaction),
     * capturing the mocked TransactionStatus passed to it. */
    private void stubTransactionToRunCallback() {
        transactionStatus = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
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
    void provisionInsertsPortfolioAfterCredentialAndBeforeTokenSigning() throws Exception {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode("password12345")).thenReturn("hashed");
        when(jwtSigner.signHs256(any(), eq("a@b.com"), eq("Alice"), eq(false))).thenReturn("jwt");

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "  Alice  ");
        service().provision(req).block();

        // Task 2.1: insertPortfolio is wired after the email DuplicateKeyException catch and
        // before token signing. Persistence tests cannot distinguish "inserted then rolled back"
        // from "never inserted"; this InOrder proof is the only test that does.
        InOrder order = inOrder(repository, jwtSigner);
        order.verify(repository).insertUser(any(), eq("a@b.com"), eq("Alice"));
        order.verify(repository).insertCredential(any(), eq("a@b.com"), eq("hashed"));
        order.verify(repository).insertPortfolio(any(), any());
        order.verify(jwtSigner).signHs256(any(), eq("a@b.com"), eq("Alice"), eq(false));
    }

    @Test
    void duplicateKeyOnInsertMapsToDuplicateEmailException() {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        doThrow(new DuplicateKeyException("dup")).when(repository).insertUser(any(), any(), any());

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "Alice");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(DuplicateEmailException.class);

        // Req 2.2, 2.7, 2.8, 1.9: the transaction must actually be marked rollback-only, not
        // just have the exception thrown past it.
        verify(transactionStatus).setRollbackOnly();
    }

    @Test
    void jwtSigningFailureRollsBackTransactionAndMapsToProvisioningFailed() throws Exception {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(jwtSigner.signHs256(any(), eq("a@b.com"), eq("Alice"), eq(false)))
                .thenThrow(new JOSEException("signing failed"));

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "Alice");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(ProvisioningFailedException.class);

        // Req 2.2: any failure during the transaction (not just duplicate email) must roll back.
        verify(transactionStatus).setRollbackOnly();
        // The inserts still ran (only the token mint failed) — confirms this is the "signing
        // failed after both inserts succeeded" path, not some earlier short-circuit.
        verify(repository).insertUser(any(), eq("a@b.com"), eq("Alice"));
        verify(repository).insertCredential(any(), eq("a@b.com"), eq("hashed"));
    }
}
