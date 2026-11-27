package kr.remerge.stylehub.domain.order.repository;

import kr.remerge.stylehub.domain.order.entity.Order;
import kr.remerge.stylehub.domain.order.entity.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 구매자 주문 목록 (상태별). GET /api/buyer/orders?status=PAID
    @Query("select o from Order o where o.buyer.userId = :buyerId and o.status = :status order by o.createdAt desc")
    List<Order> findByBuyerAndStatus(Long buyerId, OrderStatus status, Pageable pageable);
}
