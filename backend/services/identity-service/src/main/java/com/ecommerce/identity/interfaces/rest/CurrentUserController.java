package com.ecommerce.identity.interfaces.rest;

import com.ecommerce.identity.application.model.UserProfile;
import com.ecommerce.identity.application.service.AuthenticationService;
import com.ecommerce.platform.common.api.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity")
public class CurrentUserController {

    private final AuthenticationService authenticationService;

    public CurrentUserController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> currentUser(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(authenticationService.currentUser(Long.valueOf(jwt.getSubject())));
    }
}
