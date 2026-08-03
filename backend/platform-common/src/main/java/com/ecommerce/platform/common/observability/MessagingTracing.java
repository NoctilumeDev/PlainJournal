package com.ecommerce.platform.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps messaging instrumentation on the Micrometer Tracing API so the
 * OpenTelemetry SDK and the trace backend remain replaceable infrastructure.
 */
public final class MessagingTracing {

    private final Tracer tracer;
    private final Propagator propagator;

    public MessagingTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public Map<String, String> capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return Map.of();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return Map.copyOf(carrier);
    }

    public String currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().traceId();
    }

    public void inSpan(
            String name,
            Span.Kind kind,
            Map<String, String> parentContext,
            Map<String, String> tags,
            CheckedRunnable action) throws Exception {
        Span.Builder builder = parentContext == null || parentContext.isEmpty()
                ? tracer.spanBuilder()
                : propagator.extract(parentContext, Map::get);
        builder.name(name).kind(kind);
        tags.forEach(builder::tag);
        Span span = builder.start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } catch (Exception exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
