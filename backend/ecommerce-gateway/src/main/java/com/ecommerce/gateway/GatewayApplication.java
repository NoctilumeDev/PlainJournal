package com.ecommerce.gateway;

import com.ecommerce.gateway.ratelimit.GatewayRateLimitProperties;
import com.ecommerce.gateway.security.GatewayTokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
@EnableConfigurationProperties({
        GatewayRateLimitProperties.class,
        GatewayTokenProperties.class
})
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
