package com.ecommerce.catalog.application.port;

@FunctionalInterface
public interface CatalogReadRequirement {

    boolean requiresPrimary();
}
