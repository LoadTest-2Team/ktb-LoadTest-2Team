package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.ConnectedUsers;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionLoginHandlerTest {

    @Mock private SocketIOServer socketIOServer;
    @Mock private ConnectedUsers connectedUsers;
    @Mock private UserRooms userRooms;
    @Mock private RoomJoinHandler roomJoinHandler;
    @Mock private RoomLeaveHandler roomLeaveHandler;
    @Mock private SocketIOClient client;
    @Mock private ScheduledExecutorService scheduledExecutor;

    private ConnectionLoginHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConnectionLoginHandler(
                socketIOServer,
                connectedUsers,
                userRooms,
                roomJoinHandler,
                roomLeaveHandler,
                new SimpleMeterRegistry(),
                scheduledExecutor);
    }

    @Test
    void onConnect_setsUserRejoinsRoomsStoresUserAndJoinsUserRooms() {
        SocketUser user = new SocketUser("user-1", "tester", "session-1", "socket-1");
        when(connectedUsers.get(user.id())).thenReturn(null);
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1", "room-2"));

        handler.onConnect(client, user);

        verify(client).set("user", user);
        verify(roomJoinHandler).handleJoinRoom(client, "room-1");
        verify(roomJoinHandler).handleJoinRoom(client, "room-2");
        verify(connectedUsers).set(user.id(), user);
        verify(client).joinRooms(Set.of("user:" + user.id(), "room-list"));
    }

    @Test
    void onConnect_duplicateLoginWithoutUserAgentHeader_stillSetsUser() {
        SocketUser existing = new SocketUser("user-1", "tester", "old-session", "old-socket-id");
        SocketUser newUser = new SocketUser("user-1", "tester", "new-session", "new-socket-id");

        when(connectedUsers.get(newUser.id())).thenReturn(existing);
        when(client.get("user")).thenReturn(newUser);
        when(userRooms.get(newUser.id())).thenReturn(Set.of());

        HandshakeData handshakeData = org.mockito.Mockito.mock(HandshakeData.class);
        HttpHeaders httpHeaders = org.mockito.Mockito.mock(HttpHeaders.class);
        when(client.getHandshakeData()).thenReturn(handshakeData);
        when(handshakeData.getHttpHeaders()).thenReturn(httpHeaders);
        when(httpHeaders.get("User-Agent")).thenReturn(null);
        when(client.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 0));

        BroadcastOperations broadcastOperations = org.mockito.Mockito.mock(BroadcastOperations.class);
        when(socketIOServer.getRoomOperations(existing.socketId())).thenReturn(broadcastOperations);

        assertThatCode(() -> handler.onConnect(client, newUser)).doesNotThrowAnyException();

        verify(client).set("user", newUser);
        verify(connectedUsers).set(newUser.id(), newUser);
    }

    @Test
    void onDisconnect_removesCurrentConnectionAndLeavesRooms() {
        UUID socketId = UUID.randomUUID();
        SocketUser user = new SocketUser("user-1", "tester", "session-1", socketId.toString());
        when(client.get("user")).thenReturn(user);
        when(userRooms.get(user.id())).thenReturn(Set.of("room-1"));
        when(client.getSessionId()).thenReturn(socketId);
        when(connectedUsers.get(user.id())).thenReturn(user);

        handler.onDisconnect(client);

        verify(roomLeaveHandler).handleLeaveRoom(client, "room-1");
        verify(connectedUsers).del(user.id());
        verify(client).leaveRooms(Set.of("user:" + user.id(), "room-list"));
        verify(client).del("user");
        verify(client).disconnect();
    }
}
