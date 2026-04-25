package com.wealth.gateway;

import com.nimbusds.jose.JOSEException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final String authEmail;
    private final String authPassword;
    private final String authUserId;
    private final String authName;
    private final JwtSigner jwtSigner;

    public AuthController(
            @Value("${app.auth.email}") String authEmail,
            @Value("${app.auth.password}") String authPassword,
            @Value("${app.auth.user-id}") String authUserId,
            @Value("${app.auth.name}") String authName,
            JwtSigner jwtSigner) {
        this.authEmail = authEmail;
        this.authPassword = authPassword;
        this.authUserId = authUserId;
        this.authName = authName;
        this.jwtSigner = jwtSigner;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDtos.LoginRequest request) {
        if (!authEmail.equals(request.email()) || !authPassword.equals(request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginDtos.ErrorResponse("Invalid username or password."));
        }

        if (!jwtSigner.hasValidSecret()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new LoginDtos.ErrorResponse("AUTH_JWT_SECRET must be at least 32 bytes for HS256."));
        }

        try {
            String token = jwtSigner.signHs256(authUserId, authEmail, authName);
            return ResponseEntity.ok(new LoginDtos.LoginResponse(token, authUserId, authEmail, authName));
        } catch (JOSEException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new LoginDtos.ErrorResponse("Failed to sign authentication token."));
        }
    }
}
