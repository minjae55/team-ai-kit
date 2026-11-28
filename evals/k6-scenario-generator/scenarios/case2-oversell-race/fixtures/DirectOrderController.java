package kr.remerge.stylehub.domain.order.controller;

@RestController
@RequestMapping("/api/buyer/orders")
@RequiredArgsConstructor
public class DirectOrderController {
    private final OrderService orderService;

    // 바로 주문 (카트 없이). 요청: { "optionId": 120001, "quantity": 1, "addressId": 1 }
    // 성공 200, 재고 부족 400 { "code": "OUT_OF_STOCK" }
    @PostMapping("/direct")
    public ApiResponse<OrderResponse> direct(@AuthUser Long userId, @RequestBody @Valid DirectOrderRequest request) {
        return ApiResponse.ok(orderService.orderDirect(userId, request));
    }
}
