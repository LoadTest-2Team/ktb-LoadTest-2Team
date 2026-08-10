package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MessageReactionRequest;
import com.ktb.chatapp.dto.MessageReactionResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 리액션 처리 핸들러
 * 메시지 이모지 리액션 추가/제거 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReactionHandler {

    private final SocketIOServer socketIOServer;
    private final MongoTemplate mongoTemplate;

    @OnEvent(MESSAGE_REACTION)
    public void handleMessageReaction(SocketIOClient client, MessageReactionRequest data) {
        try {
            String userId = getUserId(client);
            if (userId == null || userId.isBlank()) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            String reactionField = "reactions." + data.getReaction();
            Update update = switch (data.getType()) {
                case "add" -> new Update().addToSet(reactionField, userId);
                case "remove" -> new Update().pull(reactionField, userId);
                case null, default -> null;
            };

            if (update == null) {
                client.sendEvent(ERROR, Map.of("message", "지원하지 않는 리액션 타입입니다."));
                return;
            }

            // read(findById) → 메모리에서 수정 → save() 통으로 덮어쓰기 방식은 두 유저가
            // 거의 동시에 같은 메시지에 리액션을 달면 나중 저장이 먼저 것을 덮어써서 유실된다.
            // findAndModify로 addToSet/pull을 원자적으로 적용해 경쟁 상태를 없앤다.
            Query query = Query.query(Criteria.where("_id").is(data.getMessageId()));
            FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);
            Message message = mongoTemplate.findAndModify(query, update, options, Message.class);

            if (message == null) {
                client.sendEvent(ERROR, Map.of("message", "메시지를 찾을 수 없습니다."));
                return;
            }

            log.debug("Message reaction processed - type: {}, reaction: {}, messageId: {}, userId: {}",
                data.getType(), data.getReaction(), message.getId(), userId);

            MessageReactionResponse response = new MessageReactionResponse(
                message.getId(),
                message.getReactions()
            );

            socketIOServer.getRoomOperations(message.getRoomId())
                .sendEvent(MESSAGE_REACTION_UPDATE, response);

        } catch (Exception e) {
            log.error("Error handling messageReaction", e);
            client.sendEvent(ERROR, Map.of(
                "message", "리액션 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user != null ? user.id() : null;
    }
}
