package com.ecommerce.chat.infrastructure.realtime;

import com.ecommerce.chat.application.exception.ChatError;
import com.ecommerce.chat.application.exception.ChatException;
import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID_ATTRIBUTE = "chatUserId";
    static final String ROLES_ATTRIBUTE = "chatRoles";

    private final ChatWebSocketTicketService ticketService;

    public ChatWebSocketHandshakeInterceptor(ChatWebSocketTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        Principal principal = request.getPrincipal();
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof Jwt jwt) {
            return authenticateJwt(jwt, authentication, response, attributes);
        }
        String ticket = ticket(request);
        if (ticket == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Optional<WebSocketTicketIdentity> identity =
                    ticketService.consume(ticket, request.getURI().getPath());
            if (identity.isEmpty()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            copyIdentity(identity.orElseThrow(), attributes);
            return true;
        } catch (ChatException exception) {
            response.setStatusCode(exception.error() == ChatError.CHAT_REALTIME_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private boolean authenticateJwt(
            Jwt jwt,
            Authentication authentication,
            ServerHttpResponse response,
            Map<String, Object> attributes) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (userId <= 0) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        copyIdentity(new WebSocketTicketIdentity(
                userId,
                authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .toList()), attributes);
        return true;
    }

    private String ticket(ServerHttpRequest request) {
        try {
            List<String> values = UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .get(ChatWebSocketTicketService.QUERY_PARAMETER);
            return values != null && values.size() == 1 && !values.get(0).isBlank()
                    ? values.get(0)
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void copyIdentity(
            WebSocketTicketIdentity identity,
            Map<String, Object> attributes) {
        attributes.put(USER_ID_ATTRIBUTE, identity.userId());
        attributes.put(ROLES_ATTRIBUTE, identity.roles());
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No post-handshake mutation is required.
    }

    @SuppressWarnings("unchecked")
    static List<String> roles(Map<String, Object> attributes) {
        Object value = attributes.get(ROLES_ATTRIBUTE);
        return value instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
    }
}
