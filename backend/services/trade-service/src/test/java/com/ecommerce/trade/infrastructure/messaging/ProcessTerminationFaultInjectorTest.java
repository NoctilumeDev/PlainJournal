package com.ecommerce.trade.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProcessTerminationFaultInjectorTest {

    @Test
    void remainsInertUnlessEnabledPointAndEventIdAllMatch() {
        AtomicInteger haltCode = new AtomicInteger();
        ProcessTerminationFaultInjector injector = injector(
                new ProcessTerminationFaultProperties(
                        true, ProcessTerminationPoint.OUTBOX_AFTER_BROKER_ACK, "event-1", 92),
                haltCode);

        injector.terminateIfArmed(ProcessTerminationPoint.OUTBOX_BEFORE_PUBLISH, "event-1");
        injector.terminateIfArmed(ProcessTerminationPoint.OUTBOX_AFTER_BROKER_ACK, "event-2");

        assertThat(haltCode).hasValue(0);
        assertThat(injector.triggered()).isFalse();
    }

    @Test
    void terminatesOnlyOnceForTheTargetedBoundaryAndEvent() {
        AtomicInteger haltCode = new AtomicInteger();
        ProcessTerminationFaultInjector injector = injector(
                new ProcessTerminationFaultProperties(
                        true, ProcessTerminationPoint.CONSUMER_AFTER_COMMIT, "event-1", 93),
                haltCode);

        injector.terminateIfArmed(ProcessTerminationPoint.CONSUMER_AFTER_COMMIT, "event-1");
        injector.terminateIfArmed(ProcessTerminationPoint.CONSUMER_AFTER_COMMIT, "event-1");

        assertThat(haltCode).hasValue(93);
        assertThat(injector.triggered()).isTrue();
    }

    @Test
    void disabledConfigurationNeedsNoFaultTarget() {
        AtomicInteger haltCode = new AtomicInteger();
        ProcessTerminationFaultInjector injector =
                injector(ProcessTerminationFaultProperties.disabled(), haltCode);

        injector.terminateIfArmed(ProcessTerminationPoint.OUTBOX_BEFORE_PUBLISH, "event-1");

        assertThat(haltCode).hasValue(0);
        assertThat(injector.triggered()).isFalse();
    }

    @Test
    void enabledConfigurationRequiresPointAndTarget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessTerminationFaultProperties(true, null, "event-1", 91));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessTerminationFaultProperties(
                        true, ProcessTerminationPoint.OUTBOX_BEFORE_PUBLISH, " ", 91));
    }

    private ProcessTerminationFaultInjector injector(
            ProcessTerminationFaultProperties properties,
            AtomicInteger haltCode) {
        return new ProcessTerminationFaultInjector(properties) {
            @Override
            void halt(int exitCode) {
                haltCode.compareAndSet(0, exitCode);
            }
        };
    }
}
