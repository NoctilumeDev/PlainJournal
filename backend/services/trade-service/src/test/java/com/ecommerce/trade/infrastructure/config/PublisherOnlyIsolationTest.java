package com.ecommerce.trade.infrastructure.config;

import com.ecommerce.platform.common.observability.MessagingTracing;
import com.ecommerce.trade.application.service.FlashSaleOrderRecoveryJob;
import com.ecommerce.trade.application.service.FlashSaleOrderService;
import com.ecommerce.trade.application.port.DomainEventPublisher;
import com.ecommerce.trade.infrastructure.messaging.ConsumerFailureRecorder;
import com.ecommerce.trade.infrastructure.messaging.FlashSaleAdmissionConsumer;
import com.ecommerce.trade.infrastructure.messaging.FlashSaleConsumerProperties;
import com.ecommerce.trade.infrastructure.messaging.OutboxClaimService;
import com.ecommerce.trade.infrastructure.messaging.OutboxProperties;
import com.ecommerce.trade.infrastructure.messaging.OutboxPublisherJob;
import com.ecommerce.trade.infrastructure.messaging.ProcessTerminationFaultInjector;
import com.ecommerce.trade.infrastructure.messaging.ProcessTerminationFaultProperties;
import com.ecommerce.trade.infrastructure.persistence.mapper.OutboxEventMapper;
import com.ecommerce.trade.infrastructure.sharding.UnshardedTradeShardRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublisherOnlyIsolationTest {

    @Test
    void publisherProbeDoesNotRegisterFlashSaleConsumerOrRecovery() throws Exception {
        Path module = Path.of(System.getProperty("basedir"));
        String script = Files.readString(module.resolve("../../verify-trade-outbox-multi-instance.ps1"));
        int start = script.indexOf("$arguments = @(", script.indexOf("function Start-TradeInstances"));
        String arguments = script.substring(start, script.indexOf("\n        )", start));
        String[] flags = Pattern.compile("'(--[^']+)'").matcher(arguments)
                .results().map(match -> match.group(1)).toArray(String[]::new);
        assertThat(flags).contains("--ecommerce.trade.outbox.enabled=true",
                "--ecommerce.trade.payment-consumer.enabled=false");
        // Read the actual runner flags over the production defaults, without launching
        // its Docker orchestration or allowing scheduled work to contact a broker.
        context().withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource(flags))).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getEnvironment().getProperty("ecommerce.trade.outbox.enabled"))
                            .isEqualTo("true");
                    assertThat(ctx).doesNotHaveBean(FlashSaleAdmissionConsumer.class)
                            .doesNotHaveBean(FlashSaleOrderRecoveryJob.class);
                    assertThat(ctx).hasSingleBean(OutboxPublisherJob.class);
                    var tasks = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    tasks.iterator().next().getTask().getRunnable().run();
                    verify(ctx.getBean(OutboxClaimService.class))
                            .claimBatch(anyString(), any(), anyInt());
                });
    }

    @Test
    void normalServiceStillRegistersItsFlashSaleConsumerAndRecovery() throws Exception {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(FlashSaleAdmissionConsumer.class)
                    .hasSingleBean(FlashSaleOrderRecoveryJob.class);
            assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks()).hasSize(3);
        });
    }

    private ApplicationContextRunner context() throws Exception {
        var defaults = new YamlPropertySourceLoader().load("production",
                new FileSystemResource(Path.of(System.getProperty("basedir"),
                        "src/main/resources/application.yml")));
        return new ApplicationContextRunner().withUserConfiguration(ProbeConfiguration.class)
                .withInitializer(ctx -> defaults.forEach(source -> ctx.getEnvironment()
                        .getPropertySources().addLast(source)))
                .withBean(FlashSaleOrderService.class, () -> mock(FlashSaleOrderService.class))
                .withBean(ConsumerFailureRecorder.class, () -> mock(ConsumerFailureRecorder.class))
                .withBean(MessagingTracing.class, () -> mock(MessagingTracing.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(OutboxClaimService.class, () -> {
                    OutboxClaimService claims = mock(OutboxClaimService.class);
                    when(claims.claimBatch(anyString(), any(), anyInt()))
                            .thenReturn(new OutboxClaimService.ClaimBatch(List.of(), 0, 0));
                    return claims;
                })
                .withBean(DomainEventPublisher.class, () -> mock(DomainEventPublisher.class))
                .withBean(ProcessTerminationFaultInjector.class, () ->
                        new ProcessTerminationFaultInjector(ProcessTerminationFaultProperties.disabled()))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(UnshardedTradeShardRouter.class, UnshardedTradeShardRouter::new)
                .withBean(TradeSchedulingConfig.OUTBOX_SCHEDULER, TaskScheduler.class,
                        () -> mock(TaskScheduler.class))
                .withBean(TradeSchedulingConfig.FLASH_SALE_SCHEDULER, TaskScheduler.class,
                        () -> mock(TaskScheduler.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties({FlashSaleConsumerProperties.class, OutboxProperties.class})
    @Import({FlashSaleAdmissionConsumer.class, FlashSaleOrderRecoveryJob.class, OutboxPublisherJob.class})
    static class ProbeConfiguration { }
}
