package com.ecommerce.catalog.infrastructure.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReadRouteContextTest {

    @Test
    void primaryScopeOverridesNestedReplicaAndScopesRestorePreviousPreference() {
        assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
        assertThat(CatalogReadRouteContext.requiresPrimary()).isFalse();

        try (CatalogReadRouteContext.Scope replica = CatalogReadRouteContext.preferReplica()) {
            assertThat(CatalogReadRouteContext.shouldUseReplica()).isTrue();
            assertThat(CatalogReadRouteContext.requiresPrimary()).isFalse();
            try (CatalogReadRouteContext.Scope primary = CatalogReadRouteContext.forcePrimary()) {
                assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
                assertThat(CatalogReadRouteContext.requiresPrimary()).isTrue();
                try (CatalogReadRouteContext.Scope nestedReplica =
                             CatalogReadRouteContext.preferReplica()) {
                    assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
                    assertThat(CatalogReadRouteContext.requiresPrimary()).isTrue();
                }
            }
            assertThat(CatalogReadRouteContext.shouldUseReplica()).isTrue();
            assertThat(CatalogReadRouteContext.requiresPrimary()).isFalse();
        }

        assertThat(CatalogReadRouteContext.shouldUseReplica()).isFalse();
        assertThat(CatalogReadRouteContext.requiresPrimary()).isFalse();
    }
}
