package com.ecommerce.chat.infrastructure.messaging;

import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.application.model.ChatModels.MessageView;
import com.ecommerce.chat.application.model.ChatModels.SendMessageCommand;
import com.ecommerce.chat.application.port.ChatEventPublisher;
import com.ecommerce.chat.application.service.ChatApplicationService;
import com.ecommerce.chat.infrastructure.persistence.mapper.ChatMessageMapper;
import com.ecommerce.chat.infrastructure.persistence.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest
@Import(ChatOutboxPublisherIntegrationTest.FixedClockConfiguration.class)
class ChatOutboxPublisherIntegrationTest {

    private final ChatApplicationService chatService;
    private final OutboxEventMapper outboxMapper;
    private final ChatOutboxCompletionService completionService;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    ChatOutboxPublisherIntegrationTest(
            ChatApplicationService chatService,
            OutboxEventMapper outboxMapper,
            ChatOutboxCompletionService completionService,
            ChatMessageMapper messageMapper,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.chatService = chatService;
        this.outboxMapper = outboxMapper;
        this.completionService = completionService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @AfterEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM message_receipt");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM conversation_member");
        jdbcTemplate.update("DELETE FROM chat_conversation");
    }

    @Test
    void brokerAckPublishesOutboxAndAdvancesMessageToDispatched() {
        MessageView message = storedMessage("outbox-success");
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        when(publisher.publish(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        job(publisher, "publisher-success").publishPendingEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event", String.class)).isEqualTo("PUBLISHED");
        assertThat(messageMapper.selectById(message.id()).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void brokerFailureKeepsOutboxPendingAndMessageStored() {
        MessageView message = storedMessage("outbox-failure");
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        when(publisher.publish(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("simulated broker outage")));

        job(publisher, "publisher-failure").publishPendingEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_event", String.class))
                .contains("simulated broker outage");
        assertThat(messageMapper.selectById(message.id()).getStatus()).isEqualTo("STORED");
    }

    @Test
    void brokerAckDoesNotRegressAMessageAlreadyDeliveredByAConsumer() {
        MessageView message = storedMessage("outbox-delivered-race");
        jdbcTemplate.update(
                "UPDATE chat_message SET status = 'DELIVERED' WHERE id = ?",
                message.id());
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        when(publisher.publish(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        job(publisher, "publisher-delivered-race").publishPendingEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event", String.class)).isEqualTo("PUBLISHED");
        assertThat(messageMapper.selectById(message.id()).getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void brokerAckCannotPublishOutboxWhenItsMessageIsMissing() {
        MessageView message = storedMessage("outbox-missing-message");
        jdbcTemplate.update("DELETE FROM message_receipt WHERE message_id = ?", message.id());
        jdbcTemplate.update("DELETE FROM chat_message WHERE id = ?", message.id());
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        when(publisher.publish(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        job(publisher, "publisher-missing-message").publishPendingEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_event", String.class))
                .contains("message is missing or has an invalid dispatch state");
    }

    private MessageView storedMessage(String idempotencyKey) {
        Actor customer = new Actor(9101L, false);
        Long conversationId = chatService.createConversation(
                customer,
                new CreateConversationCommand(
                        idempotencyKey + "-conversation",
                        "Outbox publisher integration",
                        null,
                        null)).id();
        return chatService.sendMessage(
                customer,
                conversationId,
                new SendMessageCommand(
                        idempotencyKey + "-message",
                        "TEXT",
                        "Persist before attempting realtime publication."));
    }

    private ChatOutboxPublisherJob job(ChatEventPublisher publisher, String publisherId) {
        ChatOutboxProperties properties = new ChatOutboxProperties(
                true,
                "127.0.0.1:18082",
                "ecommerce-chat-events-test",
                0,
                1000,
                Duration.ofSeconds(5),
                20,
                publisherId,
                Duration.ofSeconds(30));
        return new ChatOutboxPublisherJob(
                outboxMapper,
                completionService,
                publisher,
                properties,
                objectMapper,
                clock,
                new SimpleMeterRegistry());
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock chatOutboxTestClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-24T00:00:00.999999999Z"),
                    ZoneOffset.UTC);
        }
    }
}
