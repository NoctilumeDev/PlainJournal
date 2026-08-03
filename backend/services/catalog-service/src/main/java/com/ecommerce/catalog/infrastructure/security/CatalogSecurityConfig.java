package com.ecommerce.catalog.infrastructure.security;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.security.MetricsScrapeAuthenticationFilter;
import com.ecommerce.platform.common.security.MetricsScrapeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
        CatalogTokenProperties.class,
        MetricsScrapeProperties.class
})
public class CatalogSecurityConfig {

    @Bean
    public SecretKey catalogJwtSecretKey(CatalogTokenProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder catalogJwtDecoder(SecretKey catalogJwtSecretKey, CatalogTokenProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(catalogJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    public SecurityFilterChain catalogSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            MetricsScrapeProperties metricsScrapeProperties) throws Exception {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/catalog/status",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info"
                        ).permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("METRICS")
                        .requestMatchers("/actuator/consumerfailures").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/catalog/categories",
                                "/api/v1/catalog/brands",
                                "/api/v1/catalog/products",
                                "/api/v1/catalog/products/**",
                                "/api/v1/catalog/search/products"
                        ).permitAll()
                        .requestMatchers("/api/v1/catalog/admin/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(
                                "/api/v1/catalog/review-eligibilities",
                                "/api/v1/catalog/reviews",
                                "/api/v1/catalog/reviews/**"
                        ).hasRole("CUSTOMER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    ApiResponse.failure("UNAUTHORIZED", "Authentication is required"));
                        }))
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    ApiResponse.failure("FORBIDDEN", "Access is denied"));
                        }));
        http.addFilterBefore(
                new MetricsScrapeAuthenticationFilter(metricsScrapeProperties),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
