package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Deliberately NOT a {@code @Service} (component-scanned) bean: it depends on
 * {@link UserCredentialRepository}, which only exists where {@link GatewayAuthDataConfig}
 * activates (i.e. {@code spring.datasource.url} is set). Component-scanning this class
 * unconditionally would make Spring eagerly try to instantiate it as a singleton in every
 * profile's ApplicationContext, failing with an unsatisfied {@code UserCredentialRepository}
 * dependency wherever no datasource is configured (or, for tests, not opted into) — this bit
 * {@code UserCredentialRepository} itself before Task 2's fix round, hence the same fix here:
 * it is only instantiated via {@link GatewayAuthDataConfig}'s explicit {@code @Bean} method.
 */
public class AuthenticationService {

    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtSigner jwtSigner;

    public AuthenticationService(
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner) {
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtSigner = jwtSigner;
    }

    public Mono<LoginResponse> authenticate(com.wealth.gateway.LoginDtos.LoginRequest req) {
        // Req 3.9: reject blank/missing fields with the Uniform_Auth_Error BEFORE any hashing —
        // no hasher call, no mint.
        if (isBlank(req.email()) || isBlank(req.password())) {
            return Mono.error(new InvalidCredentialsException());
        }
        return Mono.fromCallable(() -> {
                    var cred = credentialRepository.findByEmailIgnoreCase(req.email());
                    if (cred.isEmpty()) {
                        // Req 3.4: burn equivalent CPU against a fixed dummy hash, then fail uniformly.
                        passwordEncoder.matches(req.password(), PasswordHasherConfig.DUMMY_PASSWORD_HASH);
                        throw new InvalidCredentialsException();
                    }
                    var row = cred.get();
                    // Req 4.6: absent/malformed stored hash -> uniform 401, but still run a REAL
                    // matches() call (against the dummy hash as a fallback) rather than
                    // short-circuiting on null/blank — a short-circuit here would let this branch
                    // return near-instantly, creating a timing oracle that distinguishes "email
                    // exists but has a corrupted/null credential row" from the other two 401
                    // outcomes (unknown email, wrong password against a real hash), defeating
                    // Req 3.4's timing-equalization goal.
                    String storedHash = row.passwordHash();
                    String hashToCheck = (storedHash != null && !storedHash.isBlank())
                            ? storedHash
                            : PasswordHasherConfig.DUMMY_PASSWORD_HASH;
                    boolean matches = passwordEncoder.matches(req.password(), hashToCheck);
                    if (!matches) {
                        throw new InvalidCredentialsException();
                    }
                    String token;
                    try {
                        token = jwtSigner.signHs256(row.userId(), row.email(), row.name(), row.readOnly());
                    } catch (com.nimbusds.jose.JOSEException e) {
                        throw new ProvisioningFailedException(e);
                    }
                    return new LoginResponse(token, row.userId(), row.email(), row.name());
                })
                .subscribeOn(Schedulers.boundedElastic()) // Req 2.5: never block the event loop
                .onErrorMap(
                        ex -> ex instanceof DataAccessException,
                        ex -> new CredentialStoreUnavailableException(ex)); // Req 3.10
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
