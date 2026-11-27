package kr.remerge.stylehub.domain.product.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    // 상품 목록. 응답: { "data": [ { "productId": 1, "name": "...", "options": [ { "optionId": 10, "stock": 120 } ] } ] }
    @GetMapping
    public ApiResponse<List<ProductSummaryResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(productService.list(page, size));
    }
}
