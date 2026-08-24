package com.summit.harnessexample;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket handler that keeps all connected front-end sessions alive and
 * broadcasts agent runtime events to them in real-time.
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket connected, sessionId={}, total={}", session.getId(), sessions.size());
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket closed, sessionId={}, status={}, total={}", session.getId(), status, sessions.size());
        super.afterConnectionClosed(session, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("WebSocket transport error, sessionId={}, error={}", session.getId(), exception.getMessage());
        sessions.remove(session);
    }

    /**
     * Broadcast a raw JSON message to all open WebSocket sessions.
     *
     * @param payload the JSON payload to send
     */
    public void broadcast(String payload) {
        for (WebSocketSession session : sessions) {
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("Failed to send message to session {}, error={}", session.getId(), e.getMessage());
                sessions.remove(session);
            }
        }
    }

    public int connectedCount() {
        return sessions.size();
    }
}
