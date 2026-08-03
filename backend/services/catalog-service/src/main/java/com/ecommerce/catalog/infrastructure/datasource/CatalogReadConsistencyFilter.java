package com.ecommerce.catalog.infrastructure.datasource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class CatalogReadConsistencyFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Catalog-Read-Consistency";
    private static final String PRIMARY = "primary";

    private final CatalogDataSourceMetrics metrics;

    CatalogReadConsistencyFilter(CatalogDataSourceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!PRIMARY.equalsIgnoreCase(request.getHeader(HEADER_NAME))) {
            filterChain.doFilter(request, response);
            return;
        }
        metrics.recordPrimaryHint();
        try (CatalogReadRouteContext.Scope ignored = CatalogReadRouteContext.forcePrimary()) {
            filterChain.doFilter(request, response);
        }
    }
}
