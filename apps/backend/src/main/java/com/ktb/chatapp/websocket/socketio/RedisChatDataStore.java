package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

/**
 * Redis(Redisson) 기반 ChatDataStore 구현.
 * LocalChatDataStore(ConcurrentHashMap)는 인스턴스별 로컬 메모리라 WAS를 여러 대로 늘리면
 * ConnectedUsers/UserRooms 상태가 인스턴스마다 따로 놀았다 — 이 구현은 RMap으로 모든 인스턴스가
 * 같은 Redis 데이터를 공유하게 한다.
 *
 * 이 맵은 ConnectedUsers(SocketUser)와 UserRooms(Set&lt;String&gt;)가 같은 맵을 서로 다른
 * 값 타입으로 공유한다 — SocketIOConfig의 RedissonClient 기본 코덱에 Jackson 다형 타입 정보
 * (JAVA_LANG_OBJECT)가 켜져 있어야 역직렬화가 된다 (선언 타입이 Object라 타입 정보 없인 실패).
 */
public class RedisChatDataStore implements ChatDataStore {

    private static final String MAP_NAME = "chat:data-store";

    private final RMap<String, Object> storage;

    public RedisChatDataStore(RedissonClient redissonClient) {
        this.storage = redissonClient.getMap(MAP_NAME);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }

    @Override
    public void delete(String key) {
        storage.remove(key);
    }

    @Override
    public int size() {
        return storage.size();
    }
}
