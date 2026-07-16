package com.ecommerce.poc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.ecommerce.poc.mysql")
@SpringBootApplication
public class MiddlewareCompatibilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiddlewareCompatibilityApplication.class, args);
    }
}

