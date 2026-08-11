package com.wealth.gateway;

import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.InvalidCredentialsException;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Example test for Uniform_Auth_Error constant identity (Req 3.5, 3.6, 10.6): the unknown-email
 * and wrong-password 401 bodies must be byte-for-byte identical.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerUniformErrorTest {

    @Mock AuthenticationService authService;
    @Mock SignupService signupService;

    @Test
    void unknownEmailAndWrongPasswordProduceByteIdenticalBodies() throws Exception {
        AuthController controller = new AuthController(authService, signupService);
        JsonMapper mapper = JsonMapper.builder().build();

        when(authService.authenticate(new LoginDtos.LoginRequest("nobody@x.com", "pw")))
                .thenReturn(Mono.error(new InvalidCredentialsException()));
        when(authService.authenticate(new LoginDtos.LoginRequest("known@x.com", "wrongpw")))
                .thenReturn(Mono.error(new InvalidCredentialsException()));

        var unknownEmailResponse = controller.login(new LoginDtos.LoginRequest("nobody@x.com", "pw")).block();
        var wrongPasswordResponse = controller.login(new LoginDtos.LoginRequest("known@x.com", "wrongpw")).block();

        byte[] unknownBytes = mapper.writeValueAsBytes(unknownEmailResponse.getBody());
        byte[] wrongPasswordBytes = mapper.writeValueAsBytes(wrongPasswordResponse.getBody());

        assertThat(unknownEmailResponse.getStatusCode().value()).isEqualTo(401);
        assertThat(wrongPasswordResponse.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownBytes).isEqualTo(wrongPasswordBytes);
    }
}
