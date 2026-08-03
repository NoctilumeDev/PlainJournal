package com.ecommerce.platform.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public final class MetricsScrapeAuthenticationFilter extends OncePerRequestFilter {

    public static final String SCRAPE_PATH = "/actuator/prometheus";
    public static final String TOKEN_HEADER = "X-Metrics-Token";

    private final MetricsScrapeProperties properties;

    public MetricsScrapeAuthenticationFilter(MetricsScrapeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getContextPath() + SCRAPE_PATH).equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedToken = request.getHeader(TOKEN_HEADER);
        if (suppliedToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!properties.enabled() || !constantTimeEquals(suppliedToken, properties.token())) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid metrics scrape credential");
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "metrics-scraper",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String suppliedToken, String configuredToken) {
        return MessageDigest.isEqual(
                suppliedToken.getBytes(StandardCharsets.UTF_8),
                configuredToken.getBytes(StandardCharsets.UTF_8));
    }

}
