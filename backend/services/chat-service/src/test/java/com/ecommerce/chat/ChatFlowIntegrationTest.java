package com.ecommerce.chat;

import com.ecommerce.chat.application.model.ChatModels.Actor;
import com.ecommerce.chat.application.model.ChatModels.ConversationView;
import com.ecommerce.chat.application.model.ChatModels.CreateConversationCommand;
import com.ecommerce.chat.application.model.ChatModels.MessageView;
import com.ecommerce.chat.application.model.ChatModels.SendMessageCommand;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketView;
import com.ecommerce.chat.application.service.ChatApplicationService;
import com.ecommerce.chat.infrastructure.realtime.ChatWebSocketTicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@AutoConfigureMockMvc
@ActiveProfiles("test")
@SpringBootTest
class ChatFlowIntegrationTest {

    private static final long CUSTOMER_ID = 1001L;
    private static final long OTHER_CUSTOMER_ID = 1002L;
    private static final long AGENT_ID = 2001L;
    private static final long OTHER_AGENT_ID = 2002L;

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ChatApplicationService chatService;

    @MockitoBean
    private ChatWebSocketTicketService webSocketTicketService;

    @Autowired
    ChatFlowIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            ChatApplicationService chatService) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.chatService = chatService;
    }

    @AfterEach
    void cleanChatData() {
        jdbcTemplate.update("DELETE FROM message_receipt");
        jdbcTemplate.update("DELETE FROM chat_attachment");
        jdbcTemplate.update("DELETE FROM chat_attachment_upload");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM conversation_member");
        jdbcTemplate.update("DELETE FROM chat_conversation");
    }

    @Test
    void persistsBeforeAcknowledgementAndSupportsIdempotencyClaimReadAndPagination() throws Exception {
        JsonNode conversation = responseJson(mockMvc.perform(post("/api/v1/chat/conversations")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientConversationId", "conversation-request-1",
                                "subject", "Question about delivery",
                                "contextType", "ORDER",
                                "contextId", "ORDER-20260723-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.unreadCount").value(0))
                .andReturn().getResponse().getContentAsString());
        String conversationId = conversation.at("/data/id").asText();

        mockMvc.perform(post("/api/v1/chat/conversations")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientConversationId", "conversation-request-1",
                                "subject", "Question about delivery",
                                "contextType", "ORDER",
                                "contextId", "ORDER-20260723-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(conversationId));
        assertThat(count("chat_conversation")).isEqualTo(1L);
        assertThat(count("conversation_member")).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/chat/conversations")
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientConversationId", "conversation-request-1",
                                "subject", "Different subject"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        JsonNode customerMessage = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/messages",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "customer-message-1",
                                "messageType", "TEXT",
                                "content", "Where is my parcel?"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.sequence").value(1))
                .andExpect(jsonPath("$.data.status").value("STORED"))
                .andReturn().getResponse().getContentAsString());
        String customerMessageId = customerMessage.at("/data/id").asText();

        assertThat(count("chat_message")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT destination_topic FROM outbox_event", String.class))
                .isEqualTo("ecommerce-chat-events-test");

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "customer-message-1",
                                "messageType", "TEXT",
                                "content", "Where is my parcel?"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(customerMessageId))
                .andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(count("chat_message")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "customer-message-1",
                                "messageType", "TEXT",
                                "content", "Changed content"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(get("/api/v1/chat/conversations/{conversationId}", conversationId)
                        .with(customerJwt(OTHER_CUSTOMER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONVERSATION_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/chat/conversations")
                        .with(agentJwt(AGENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(conversationId))
                .andExpect(jsonPath("$.data[0].assignedAgentId").doesNotExist());

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/claim", conversationId)
                        .with(agentJwt(AGENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAgentId").value(Long.toString(AGENT_ID)))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM message_receipt
                WHERE message_id = ? AND recipient_id = ?
                """, String.class, Long.valueOf(customerMessageId), AGENT_ID)).isEqualTo("OFFLINE");

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/claim", conversationId)
                        .with(agentJwt(OTHER_AGENT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_ALREADY_ASSIGNED"));

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/read", conversationId)
                        .with(agentJwt(AGENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lastReadMessageId", customerMessageId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadSequence").value(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM message_receipt
                WHERE message_id = ? AND recipient_id = ?
                """, String.class, Long.valueOf(customerMessageId), AGENT_ID)).isEqualTo("READ");

        JsonNode agentMessage = responseJson(mockMvc.perform(post(
                                "/api/v1/chat/conversations/{conversationId}/messages",
                                conversationId)
                        .with(agentJwt(AGENT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "agent-message-1",
                                "messageType", "text",
                                "content", "The parcel is in transit."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(2))
                .andReturn().getResponse().getContentAsString());
        String agentMessageId = agentMessage.at("/data/id").asText();
        assertThat(count("chat_message")).isEqualTo(2L);
        assertThat(count("outbox_event")).isEqualTo(2L);

        JsonNode firstPage = responseJson(mockMvc.perform(get(
                                "/api/v1/chat/conversations/{conversationId}/messages",
                                conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(agentMessageId))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextBeforeSequence").value(2))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .param("size", "1")
                        .param("beforeSequence", firstPage.at("/data/nextBeforeSequence").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(customerMessageId))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        mockMvc.perform(get("/api/v1/chat/conversations/{conversationId}", conversationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.lastMessageSequence").value(2));

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/read", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("lastReadMessageId", agentMessageId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadSequence").value(2));

        mockMvc.perform(get("/api/v1/chat/conversations/{conversationId}", conversationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM message_receipt
                WHERE message_id = ? AND recipient_id = ?
                """, String.class, Long.valueOf(agentMessageId), CUSTOMER_ID)).isEqualTo("READ");

        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/close", conversationId)
                        .with(customerJwt(OTHER_CUSTOMER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONVERSATION_ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/close", conversationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/close", conversationId)
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        mockMvc.perform(post("/api/v1/chat/conversations/{conversationId}/messages", conversationId)
                        .with(customerJwt(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "clientMessageId", "customer-message-after-close",
                                "messageType", "TEXT",
                                "content", "This must not be stored."
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_CLOSED"));
        assertThat(count("chat_message")).isEqualTo(2L);
        mockMvc.perform(get("/api/v1/chat/conversations")
                        .with(agentJwt(AGENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void issuesNoStoreBrowserWebSocketTicketForAuthenticatedChatRole() throws Exception {
        when(webSocketTicketService.issue(
                eq(CUSTOMER_ID),
                eq(List.of("ROLE_CUSTOMER"))))
                .thenReturn(new WebSocketTicketView(
                        "short-lived-ticket",
                        "/ws/chat",
                        "ticket",
                        Instant.parse("2026-07-23T08:00:30Z")));

        mockMvc.perform(post("/api/v1/chat/websocket-tickets")
                        .with(customerJwt(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticket").value("short-lived-ticket"))
                .andExpect(jsonPath("$.data.targetPath").value("/ws/chat"))
                .andExpect(jsonPath("$.data.queryParameter").value("ticket"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-07-23T08:00:30Z"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                        .contains("no-store"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.PRAGMA))
                        .isEqualTo("no-cache"));
    }

    @Test
    void concurrentClientRetriesConvergeToOneConversationAndOneMessage() throws Exception {
        Actor customer = new Actor(CUSTOMER_ID, false);
        CreateConversationCommand conversationCommand = new CreateConversationCommand(
                "concurrent-conversation-1",
                "Concurrent retry verification",
                "ORDER",
                "ORDER-CONCURRENT-1");

        List<ConversationView> conversations = runConcurrently(
                8,
                () -> chatService.createConversation(customer, conversationCommand));
        assertThat(conversations)
                .extracting(ConversationView::id)
                .containsOnly(conversations.get(0).id());
        assertThat(count("chat_conversation")).isEqualTo(1L);
        assertThat(count("conversation_member")).isEqualTo(1L);

        Long conversationId = conversations.get(0).id();
        SendMessageCommand messageCommand = new SendMessageCommand(
                "concurrent-message-1",
                "TEXT",
                "All concurrent retries must return the same stored fact.");
        List<MessageView> messages = runConcurrently(
                16,
                () -> chatService.sendMessage(customer, conversationId, messageCommand));

        assertThat(messages)
                .extracting(MessageView::id)
                .containsOnly(messages.get(0).id());
        assertThat(messages)
                .extracting(MessageView::sequence)
                .containsOnly(1L);
        assertThat(count("chat_message")).isEqualTo(1L);
        assertThat(count("outbox_event")).isEqualTo(1L);
    }

    private RequestPostProcessor customerJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of("CUSTOMER")))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor agentJwt(long userId) {
        return jwt()
                .jwt(token -> token.subject(Long.toString(userId)).claim("roles", List.of("OPERATOR")))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode responseJson(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private <T> List<T> runConcurrently(int participants, Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = java.util.stream.IntStream.range(0, participants)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent test start timed out");
                        }
                        return action.call();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(20, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException("Concurrent chat request failed", exception);
                        }
                    })
                    .toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
