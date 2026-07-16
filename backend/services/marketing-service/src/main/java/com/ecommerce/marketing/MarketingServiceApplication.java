package com.ecommerce.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.ecommerce.marketing.infrastructure.persistence.mapper")
@SpringBootApplication(scanBasePackages = {"com.ecommerce.marketing", "com.ecommerce.platform.common"})
public class MarketingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingServiceApplication.class, args);
    }
}
