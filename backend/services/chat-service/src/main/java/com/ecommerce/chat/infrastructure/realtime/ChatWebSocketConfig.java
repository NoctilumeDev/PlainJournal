package com.ecommerce.chat.infrastructure.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(
        prefix = "ecommerce.chat.realtime",
        name = "enabled",
        havingValue = "true")
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;
    private final ChatRealtimeProperties properties;

    public ChatWebSocketConfig(
            ChatWebSocketHandler handler,
            ChatWebSocketHandshakeInterceptor handshakeInterceptor,
            ChatRealtimeProperties properties) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
