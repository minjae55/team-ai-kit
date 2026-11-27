package kr.remerge.stylehub.domain.cart.controller;

import kr.remerge.stylehub.domain.cart.dto.CartAddRequest;
import kr.remerge.stylehub.domain.cart.dto.CartItemResponse;
import kr.remerge.stylehub.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ApiResponse<List<CartItemResponse>> list(@AuthUser Long userId) {
        return ApiResponse.ok(cartService.list(userId));
    }

    // 장바구니 담기. 같은 옵션이 이미 있으면 수량을 더한다 (재고 행을 조회만 하고 잠그지 않음)
    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(@AuthUser Long userId, @RequestBody @Valid CartAddRequest request) {
        return ApiResponse.ok(cartService.add(userId, request));
    }
}
