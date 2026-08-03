package com.ecommerce.fulfillment.infrastructure.geo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ecommerce.fulfillment.geo")
public record ShipmentGeoProperties(
        boolean cacheEnabled,
        boolean mysqlSpatialEnabled,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+") String namespace,
        @Min(1_000) @Max(5_000_000) long maxRadiusMeters,
        @Min(1) @Max(500) int maxResults,
        @Min(1) @Max(100_000) int rebuildLimit
) {

    public ShipmentGeoProperties {
        namespace = namespace == null || namespace.isBlank() ? "local" : namespace;
        maxRadiusMeters = maxRadiusMeters <= 0 ? 2_000_000 : maxRadiusMeters;
        maxResults = maxResults <= 0 ? 200 : maxResults;
        rebuildLimit = rebuildLimit <= 0 ? 5_000 : rebuildLimit;
    }

    public String redisGeoKey() {
        return "ecommerce:" + namespace + ":fulfillment:geo:latest";
    }

    public String redisMetadataKey(String fulfillmentNo) {
        return "ecommerce:" + namespace + ":fulfillment:geo:position:" + fulfillmentNo;
    }
}
