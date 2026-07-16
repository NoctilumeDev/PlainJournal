package com.ecommerce.identity.interfaces.rest;

import com.ecommerce.identity.application.model.AuthTokens;
import com.ecommerce.identity.application.model.LoginContext;
import com.ecommerce.identity.application.model.UserProfile;
import com.ecommerce.identity.application.service.AuthenticationService;
import com.ecommerce.platform.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserProfile>> register(@Valid @RequestBody RegisterRequest request) {
        UserProfile profile = authenticationService.register(
                request.email(),
                request.password(),
                request.displayName()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(profile));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokens> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        LoginContext context = new LoginContext(
                servletRequest.getRemoteAddr(),
                truncate(servletRequest.getHeader("User-Agent"), 300)
        );
        return ApiResponse.success(authenticationService.login(
                request.email(),
                request.password(),
                context
        ));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokens> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authenticationService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authenticationService.logout(request.refreshToken());
        return ApiResponse.success(null);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank
            @Size(min = 10, max = 64)
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                    message = "password must contain at least one letter and one digit"
            )
            String password,
            @NotBlank @Size(min = 2, max = 50) String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(max = 128) String password
    ) {
    }

    public record RefreshTokenRequest(
            @NotBlank @Size(max = 512) String refreshToken
    ) {
    }
}
