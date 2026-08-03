package com.ecommerce.catalog.infrastructure.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSearchWorkerIdConfigurationTest {

    @Test
    void usesServiceInstanceIdWhenDedicatedWorkerIdIsAbsent() {
        assertThat(resolveWorkerId(Map.of("SERVICE_INSTANCE_ID", "catalog-node-a")))
                .isEqualTo("catalog-node-a");
    }

    @Test
    void dedicatedWorkerIdOverridesServiceInstanceId() {
        assertThat(resolveWorkerId(Map.of(
                "SERVICE_INSTANCE_ID", "catalog-node-a",
                "CATALOG_SEARCH_WORKER_ID", "catalog-search-worker-a")))
                .isEqualTo("catalog-search-worker-a");
    }

    private String resolveWorkerId(Map<String, Object> overrides) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                Path.of("src", "main", "resources", "application.yml")));
        yaml.afterPropertiesSet();
        Properties application = Objects.requireNonNull(yaml.getObject());
        assertThat(application).containsKey("ecommerce.catalog.search.worker-id");
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test-overrides", overrides));
        sources.addLast(new PropertiesPropertySource(
                "catalog-application",
                application));
        return new PropertySourcesPropertyResolver(sources)
                .getRequiredProperty("ecommerce.catalog.search.worker-id");
    }
}
