package com.ecommerce.chat.infrastructure.security;

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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({
        ChatTokenProperties.class,
        MetricsScrapeProperties.class
})
public class ChatSecurityConfig {

    @Bean
    public Clock chatClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecretKey chatJwtSecretKey(ChatTokenProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder chatJwtDecoder(SecretKey chatJwtSecretKey, ChatTokenProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(chatJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    public SecurityFilterChain chatSecurityFilterChain(
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
                                "/api/v1/chat/status",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        .requestMatchers("/ws/chat").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("METRICS")
                        .requestMatchers("/actuator/consumerfailures").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/chat/conversations")
                        .hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/chat/conversations/*/claim")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/chat/admin/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/chat/**")
                        .hasAnyRole("CUSTOMER", "ADMIN", "OPERATOR")
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
