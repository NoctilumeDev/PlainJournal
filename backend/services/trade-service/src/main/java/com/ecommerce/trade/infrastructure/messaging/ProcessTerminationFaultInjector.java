package com.ecommerce.trade.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ProcessTerminationFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(ProcessTerminationFaultInjector.class);

    private final ProcessTerminationFaultProperties properties;
    private final AtomicBoolean triggered = new AtomicBoolean();

    public ProcessTerminationFaultInjector(ProcessTerminationFaultProperties properties) {
        this.properties = properties;
    }

    public void terminateIfArmed(ProcessTerminationPoint point, String eventId) {
        Objects.requireNonNull(point, "point");
        if (!properties.enabled()
                || properties.point() != point
                || !Objects.equals(properties.targetEventId(), eventId)
                || !triggered.compareAndSet(false, true)) {
            return;
        }
        log.error("M3 process termination fault triggered: point={}, eventId={}, exitCode={}",
                point, eventId, properties.exitCode());
        halt(properties.exitCode());
    }

    void halt(int exitCode) {
        Runtime.getRuntime().halt(exitCode);
    }

    boolean triggered() {
        return triggered.get();
    }
}
