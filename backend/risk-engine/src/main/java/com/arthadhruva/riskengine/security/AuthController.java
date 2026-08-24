package com.arthadhruva.riskengine.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Deliberately NOT covered by {@code AuditAspect} (see that class's doc): the request here
 * carries a raw password and the response carries a live JWT -- neither should ever be written
 * into the generic audit trail. A dedicated, secret-free login-attempt log (who logged in, when,
 * success/failure, without the credential itself) is real follow-up work, out of scope for this
 * round.
 */
@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, UserRepository userRepository, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        User user = userRepository.findByUsername(request.username()).orElseThrow();
        JwtService.IssuedToken issued = jwtService.issue(user.getUsername(), user.getRole());
        return ResponseEntity.ok(new LoginResponse(issued.token(), user.getUsername(), user.getRole().name(), issued.expiresAt()));
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, String role, Instant expiresAt) {
    }
}
