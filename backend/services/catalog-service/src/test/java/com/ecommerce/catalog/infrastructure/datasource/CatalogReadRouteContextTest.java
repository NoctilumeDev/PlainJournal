package com.ecommerce.catalog.infrastructure.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReadRouteContextTest {

    @Test
    void primaryScopeOverridesNestedReplicaAndScopesRestorePreviousPreference() {
        assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();

        try (CatalogReadRouteContext.Scope replica = CatalogReadRouteContext.preferReplica()) {
            assertThat(CatalogReadRouteContext.shouldUseReplica()).isTrue();
            try (CatalogReadRouteContext.Scope primary = CatalogReadRouteContext.forcePrimary()) {
                assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
                try (CatalogReadRouteContext.Scope nestedReplica =
                             CatalogReadRouteContext.preferReplica()) {
                    assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
                }
            }
            assertThat(CatalogReadRouteContext.shouldUseReplica()).isTrue();
        }

        assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
    }
}
