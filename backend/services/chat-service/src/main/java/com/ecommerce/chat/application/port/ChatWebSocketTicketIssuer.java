package com.ecommerce.chat.application.port;

import com.ecommerce.chat.application.model.ChatModels.WebSocketTicketView;

import java.util.List;

public interface ChatWebSocketTicketIssuer {

    WebSocketTicketView issue(Long userId, List<String> authorities);
}
