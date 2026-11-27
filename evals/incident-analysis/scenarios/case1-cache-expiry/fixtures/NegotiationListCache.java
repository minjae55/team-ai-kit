package kr.remerge.stylehub.domain.negotiation.cache;

import java.time.Duration;

// 사용자별 Redis Hash 캐시: key = negotiationList:{userId}, field = page:size
public class NegotiationListCache {
    private static final Duration TTL = Duration.ofSeconds(60);

    public void put(Integer userId, int page, int size, String json) {
        // HSET 후, 키에 만료가 없을 때만 EXPIRE key 60s (처음 저장한 시점 기준)
    }
}
