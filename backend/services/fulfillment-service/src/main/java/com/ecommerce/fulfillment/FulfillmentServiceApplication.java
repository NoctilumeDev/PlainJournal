package com.ecommerce.fulfillment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.ecommerce.fulfillment.infrastructure.persistence.mapper")
@SpringBootApplication(scanBasePackages = {"com.ecommerce.fulfillment", "com.ecommerce.platform.common"})
public class FulfillmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}
