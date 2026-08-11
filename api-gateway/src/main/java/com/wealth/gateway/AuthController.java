package com.wealth.gateway;

import com.wealth.gateway.auth.CredentialStoreUnavailableException;
import com.wealth.gateway.auth.DuplicateEmailException;
import com.wealth.gateway.auth.InvalidCredentialsException;
import com.wealth.gateway.auth.ProvisioningFailedException;
import com.wealth.gateway.auth.SignupDtos;
import com.wealth.gateway.auth.SignupService;
import com.wealth.gateway.auth.ValidationException;
import com.wealth.gateway.auth.AuthenticationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * A single pre-serialized constant written identically on every login-failure path (Req 3.5,
     * 3.6, 10.6) — unknown email, wrong password, blank fields, and absent/malformed stored hash
     * all produce this exact response, so no path reveals *why* it failed.
     */
    private static final LoginDtos.ErrorResponse UNIFORM_AUTH_ERROR =
            new LoginDtos.ErrorResponse("Invalid username or password.");

    private final AuthenticationService authService;
    private final SignupService signupService;

    public AuthController(AuthenticationService authService, SignupService signupService) {
        this.authService = authService;
        this.signupService = signupService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Object>> login(@RequestBody LoginDtos.LoginRequest request) {
        return authService.authenticate(request)
                .map(resp -> ResponseEntity.ok((Object) new LoginDtos.LoginResponse(
                        resp.token(), resp.userId(), resp.email(), resp.name())))
                .onErrorResume(InvalidCredentialsException.class, ex -> Mono.just(uniformAuthError()))
                .onErrorResume(CredentialStoreUnavailableException.class, ex -> Mono.just(serviceUnavailable()))
                // Defensive catch-all: authenticate() can in principle let other runtime
                // exceptions escape unmapped (e.g. ProvisioningFailedException from a
                // JOSEException during token signing). Placed last so it only catches what the
                // two specific handlers above didn't, and maps to a generic 500 instead of a raw
                // Spring default-error body or a Mono that never completes.
                .onErrorResume(Throwable.class, ex -> Mono.just(genericServerError()));
    }

    @PostMapping("/signup")
    public Mono<ResponseEntity<Object>> signup(@RequestBody SignupDtos.SignupRequest request) {
        return signupService.provision(request)
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body((Object) new LoginDtos.LoginResponse(
                        resp.token(), resp.userId(), resp.email(), resp.name())))
                .onErrorResume(ValidationException.class,
                        ex -> Mono.just(badRequest(ex.field())))
                .onErrorResume(DuplicateEmailException.class, ex -> Mono.just(conflict()))
                .onErrorResume(ProvisioningFailedException.class, ex -> Mono.just(provisioningFailed()))
                // Defensive catch-all (parity with login()): provision() maps most failures into
                // the three specific exceptions above, but an exception thrown inside this
                // method's own .map(...) step would otherwise escape unguarded to WebFlux's
                // default error handler instead of this app's error body shape.
                .onErrorResume(Throwable.class, ex -> Mono.just(genericServerError()));
    }

    private static ResponseEntity<Object> uniformAuthError() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNIFORM_AUTH_ERROR);
    }

    private static ResponseEntity<Object> serviceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new LoginDtos.ErrorResponse("Service temporarily unavailable."));
    }

    private static ResponseEntity<Object> genericServerError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new LoginDtos.ErrorResponse("An unexpected error occurred."));
    }

    private static ResponseEntity<Object> badRequest(String field) {
        return ResponseEntity.badRequest()
                .body(new SignupDtos.FieldErrorResponse("invalid_request", field));
    }

    private static ResponseEntity<Object> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new LoginDtos.ErrorResponse("An account with this email already exists."));
    }

    private static ResponseEntity<Object> provisioningFailed() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new LoginDtos.ErrorResponse("Account provisioning failed. Please try again."));
    }
}
