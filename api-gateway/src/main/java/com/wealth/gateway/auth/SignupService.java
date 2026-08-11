package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Deliberately NOT a {@code @Service} (component-scanned) bean — same reasoning as
 * {@link AuthenticationService}: it depends on {@link UserCredentialRepository} and
 * {@link TransactionTemplate}, both of which only exist where {@link GatewayAuthDataConfig}
 * activates. It is only instantiated via that class's explicit {@code @Bean} method.
 */
public class SignupService {

    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtSigner jwtSigner;
    private final TransactionTemplate transactionTemplate;

    public SignupService(
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner,
            TransactionTemplate transactionTemplate) {
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtSigner = jwtSigner;
        this.transactionTemplate = transactionTemplate;
    }

    public Mono<LoginResponse> provision(SignupDtos.SignupRequest req) {
        // Req 1.4-1.8, 9.2: validate before touching the database. Propagates ValidationException
        // synchronously into the Mono's error channel via fromCallable below.
        return Mono.fromCallable(() -> {
                    SignupDtos.ValidatedSignup v = SignupValidator.validate(req);
                    return transactionTemplate.execute(status -> {
                        String hash = passwordEncoder.encode(v.password()); // Req 4.1: hash, never plaintext
                        UUID userId = UUID.randomUUID(); // Req 2.3: becomes the JWT sub
                        try {
                            credentialRepository.insertUser(userId, v.email(), v.name());
                            credentialRepository.insertCredential(userId, v.email(), hash);
                        } catch (DuplicateKeyException dup) {
                            status.setRollbackOnly(); // Req 2.2, 2.7, 2.8, 1.9
                            throw new DuplicateEmailException();
                        }
                        String token;
                        try {
                            token = jwtSigner.signHs256(userId.toString(), v.email(), v.name(), false);
                        } catch (com.nimbusds.jose.JOSEException e) {
                            status.setRollbackOnly();
                            throw new ProvisioningFailedException(e);
                        }
                        return new LoginResponse(token, userId.toString(), v.email(), v.name());
                    });
                })
                .subscribeOn(Schedulers.boundedElastic()) // Req 2.5
                .onErrorMap(
                        ex -> !(ex instanceof ValidationException
                                || ex instanceof DuplicateEmailException
                                || ex instanceof ProvisioningFailedException),
                        ProvisioningFailedException::new); // Req 2.2: any other failure -> rollback + error
    }
}
