package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.ERROR;
import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGE_REACTION_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageReactionHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private SocketIOClient client;
    @Mock private BroadcastOperations roomOperations;

    private MessageReactionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageReactionHandler(socketIOServer, mongoTemplate);
    }

    @Test
    void handleMessageReaction_rejectsUnauthorizedClient() {
        when(client.get("user")).thenReturn(null);

        handler.handleMessageReaction(client, new MessageReactionRequest("👍", "message-1", "add", "👍"));

        verify(client).sendEvent(eq(ERROR), any());
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(Message.class));
    }

    @Test
    void handleMessageReaction_addsReactionAndBroadcasts() {
        // findAndModify(returnNew=true)는 원자적 addToSet이 이미 반영된 최신 상태를 돌려준다
        Message updated = Message.builder()
                .id("message-1")
                .roomId("room-1")
                .reactions(Map.of("👍", Set.of("user-1")))
                .build();
        MessageReactionRequest request =
                new MessageReactionRequest("👍", "message-1", "add", "👍");

        when(client.get("user"))
                .thenReturn(new SocketUser("user-1", "tester", "session-1", "socket-1"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Message.class)))
                .thenReturn(updated);
        when(socketIOServer.getRoomOperations("room-1")).thenReturn(roomOperations);

        handler.handleMessageReaction(client, request);

        verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Message.class));
        ArgumentCaptor<Object> responseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(roomOperations).sendEvent(eq(MESSAGE_REACTION_UPDATE), responseCaptor.capture());
        MessageReactionResponse response = (MessageReactionResponse) responseCaptor.getValue();
        assertEquals("message-1", response.getMessageId());
        assertEquals(Set.of("user-1"), response.getReactions().get("👍"));
    }
}
