package kr.remerge.stylehub.domain.negotiation.service;

public class NegotiationQueryFacade {
    public Object getNegotiationList(Integer userId, int page, int size) {
        // 캐시에 있으면 반환, 없으면 바로 DB 조회 후 캐시에 저장 (락이나 요청 합치기 없음)
        return null;
    }
}
