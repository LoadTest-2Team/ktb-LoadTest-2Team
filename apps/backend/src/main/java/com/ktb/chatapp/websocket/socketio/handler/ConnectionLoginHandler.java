package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;
    private final ScheduledExecutorService scheduledExecutor;

    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            RoomLeaveHandler roomLeaveHandler,
            MeterRegistry meterRegistry,
            ScheduledExecutorService socketIoScheduledExecutor) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.roomLeaveHandler = roomLeaveHandler;
        this.scheduledExecutor = socketIoScheduledExecutor;

        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", connectedUsers::size)
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }
    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    public void onConnect(SocketIOClient client, SocketUser user) {
        String userId = user.id();
        
        try {
            notifyDuplicateLogin(client, userId);
            client.set("user", user);
            
            userRooms.get(userId).forEach(roomId -> {
                // 재접속 시 기존 참여 방 재입장 처리
                roomJoinHandler.handleJoinRoom(client, roomId);
            });
            
            connectedUsers.set(userId, user);

            log.info("Socket.IO user connected: {} ({}) - Total concurrent users: {}",
                    getUserName(client), userId, connectedUsers.size());

            client.joinRooms(Set.of("user:" + userId, "room-list"));
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO connection", e);
            client.sendEvent(ERROR, Map.of(
                    "message", "연결 처리 중 오류가 발생했습니다."
            ));
        }
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);
        
        try {
            if (userId == null) {
                return;
            }
            
            userRooms.get(userId).forEach(roomId -> {
                roomLeaveHandler.handleLeaveRoom(client, roomId);
            });
            String socketId = client.getSessionId().toString();
            
            // 해당 사용자의 현재 활성 연결인 경우에만 정리
            var socketUser = connectedUsers.get(userId);
            if (socketUser != null && socketId.equals(socketUser.socketId())) {
                connectedUsers.del(userId);
            } else {
                log.warn("Socket.IO disconnect: User {} has a different active connection. Skipping cleanup.", userId);
            }

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
            client.disconnect();

            log.info("Socket.IO user disconnected: {} ({}) - Total concurrent users: {}",
                    userName, userId, connectedUsers.size());
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }
    
    /**
     * socketIOServer.getClient()는 이 인스턴스에 로컬로 붙어있는 클라이언트만 찾을 수 있어서
     * 기존 세션이 다른 인스턴스에 붙어있으면 못 찾았다 (멀티 클러스터에서  불가).
     * 모든 클라이언트는 자기 sessionId와 같은 이름의 room에 자동 가입돼 있으므로,
     * 그 room으로 보내면 어느 인스턴스에 붙어있든 클러스터 전체에서 정확히 그 세션에만 전달된다.
     * ("user:" + userId room은 새 클라이언트도 곧 같이 들어오므로 10초 뒤 SESSION_ENDED가
     * 새 세션까지 잘못 맞힐 수 있어 쓰지 않는다.)
     */
    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) {
            return;
        }
        String existingSocketId = socketUser.socketId();

        String deviceInfo = client.getHandshakeData().getHttpHeaders().get("User-Agent");
        socketIOServer.getRoomOperations(existingSocketId).sendEvent(DUPLICATE_LOGIN, Map.of(
                "type", "new_login_attempt",
                "deviceInfo", deviceInfo != null ? deviceInfo : "unknown",
                "ipAddress", client.getRemoteAddress().toString(),
                "timestamp", System.currentTimeMillis()
        ));

        scheduledExecutor.schedule(() -> {
            try {
                socketIOServer.getRoomOperations(existingSocketId).sendEvent(SESSION_ENDED, Map.of(
                        "reason", "duplicate_login",
                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
                ));
            } catch (Exception e) {
                log.error("Error sending delayed SESSION_ENDED notification", e);
            }
        }, 10, TimeUnit.SECONDS);
    }
}
