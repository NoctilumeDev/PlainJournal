package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatWebSocketHandshakeInterceptorTest {

    private final ChatWebSocketTicketService ticketService =
            mock(ChatWebSocketTicketService.class);
    private final ChatWebSocketHandshakeInterceptor interceptor =
            new ChatWebSocketHandshakeInterceptor(ticketService);

    @Test
    void acceptsNumericJwtSubjectAndCopiesAuthorities() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Jwt jwt = jwt("9301");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        jwt,
                        jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        when(request.getPrincipal()).thenReturn(authentication);
        Map<String, Object> attributes = new HashMap<>();

        assertThat(interceptor.beforeHandshake(
                request,
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes)).isTrue();
        assertThat(attributes.get(ChatWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE))
                .isEqualTo(9301L);
        assertThat(ChatWebSocketHandshakeInterceptor.roles(attributes))
                .containsExactly("ROLE_CUSTOMER");
        verifyNoInteractions(ticketService);
    }

    @Test
    void rejectsNonNumericJwtSubject() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Jwt jwt = jwt("not-a-user-id");
        when(request.getPrincipal()).thenReturn(
                new UsernamePasswordAuthenticationToken(jwt, jwt, new ArrayList<>()));

        ServerHttpResponse response = mock(ServerHttpResponse.class);
        assertThat(interceptor.beforeHandshake(
                request,
                response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptsSingleUseBrowserTicketWithoutAuthorizationHeader() {
        String ticket = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(
                "http://localhost/ws/chat?ticket=" + ticket));
        when(ticketService.consume(ticket, "/ws/chat"))
                .thenReturn(Optional.of(new WebSocketTicketIdentity(
                        9302L,
                        List.of("ROLE_OPERATOR"))));
        Map<String, Object> attributes = new HashMap<>();

        assertThat(interceptor.beforeHandshake(
                request,
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes)).isTrue();
        assertThat(attributes.get(ChatWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE))
                .isEqualTo(9302L);
        assertThat(ChatWebSocketHandshakeInterceptor.roles(attributes))
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    void rejectsMissingOrRepeatedTicket() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create(
                "http://localhost/ws/chat?ticket=first&ticket=second"));

        assertThat(interceptor.beforeHandshake(
                request,
                response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(ticketService);
    }

    @Test
    void returnsServiceUnavailableWhenTicketStoreCannotBeRead() {
        String ticket = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create(
                "http://localhost/ws/chat?ticket=" + ticket));
        when(ticketService.consume(ticket, "/ws/chat"))
                .thenThrow(new ChatException(ChatError.CHAT_REALTIME_UNAVAILABLE));

        assertThat(interceptor.beforeHandshake(
                request,
                response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private Jwt jwt(String subject) {
        return new Jwt(
                "test-token",
                Instant.parse("2026-07-23T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"),
                Map.of("alg", "HS256"),
                Map.of("sub", subject));
    }
}
