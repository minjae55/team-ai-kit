package kr.remerge.stylehub.domain.product.service;

import org.springframework.data.domain.*;

@Service
@RequiredArgsConstructor
public class AdminProductService {
    private final ProductRepository productRepository;

    // GET /api/admin/products?page=N  (한 페이지 20개, 최신순)
    public Page<Product> list(int page) {
        return productRepository.findAll(PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "productId")));
    }
}
