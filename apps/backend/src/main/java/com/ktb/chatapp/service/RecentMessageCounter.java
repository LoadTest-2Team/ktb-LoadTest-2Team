package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    /**
     * 방 여러 개의 최근 메시지 수를 한 번의 집계 쿼리로 조회한다.
     * RoomService.getAllRooms가 방마다 countRecentMessagesByRoomId를 반복 호출하던 N+1 제거용.
     */
    public Map<String, Integer> countRecentMessages(Collection<String> roomIds) {
        if (roomIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where("room").in(roomIds).and("timestamp").gte(since)),
            Aggregation.group("room").count().as("count")
        );

        Map<String, Integer> counts = new HashMap<>();
        mongoTemplate.aggregate(aggregation, "messages", RoomMessageCount.class)
            .forEach(result -> counts.put(result.id(), result.count()));
        return counts;
    }

    private record RoomMessageCount(String id, int count) {
    }
}
