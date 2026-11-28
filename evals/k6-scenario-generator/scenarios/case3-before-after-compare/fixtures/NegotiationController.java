package kr.remerge.stylehub.domain.negotiation.controller;

@RestController
@RequestMapping("/api/negotiations")
@RequiredArgsConstructor
public class NegotiationController {
    private final NegotiationQueryService queryService;

    // 내 협의 목록. 구매자별 캐시(TTL 60초 + jitter). 응답 { "data": [ ... ] }
    @GetMapping
    public ApiResponse<List<NegotiationSummary>> list(@AuthUser Long userId) {
        return ApiResponse.ok(queryService.list(userId));
    }
}
