package com.ecommerce.trade.infrastructure.security;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.security.MetricsScrapeAuthenticationFilter;
import com.ecommerce.platform.common.security.MetricsScrapeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
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
@EnableConfigurationProperties({
        TradeTokenProperties.class,
        InternalServiceProperties.class,
        MetricsScrapeProperties.class
})
public class TradeSecurityConfig {

    @Bean
    public SecretKey tradeJwtSecretKey(TradeTokenProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder tradeJwtDecoder(SecretKey tradeJwtSecretKey, TradeTokenProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(tradeJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    public SecurityFilterChain tradeSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            InternalServiceAuthenticationFilter internalServiceAuthenticationFilter,
            MetricsScrapeProperties metricsScrapeProperties) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/trade/status",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/api/v1/trade/status/distributed-id"
                        ).permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "METRICS")
                        .requestMatchers(
                                "/actuator/metrics", "/actuator/metrics/**",
                                "/actuator/consumerfailures", "/actuator/consumerfailures/**",
                                "/actuator/businessprocesses", "/actuator/businessprocesses/**"
                        ).hasRole("ADMIN")
                        .requestMatchers("/api/v1/trade/internal/**").hasRole("INTERNAL_SERVICE")
                        .requestMatchers("/api/v1/trade/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/trade/**").hasAnyRole("CUSTOMER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    ApiResponse.failure("UNAUTHORIZED", "Authentication is required"));
                        }))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(),
                            ApiResponse.failure("FORBIDDEN", "Access is denied"));
                }));
        http.addFilterBefore(
                new MetricsScrapeAuthenticationFilter(metricsScrapeProperties),
                BearerTokenAuthenticationFilter.class);
        http.addFilterBefore(internalServiceAuthenticationFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
