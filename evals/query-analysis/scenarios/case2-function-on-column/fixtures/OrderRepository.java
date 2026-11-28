package kr.remerge.stylehub.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 일별 정산. GET /api/admin/settlements?day=2026-11-20
    @Query(value = "select * from orders where date(created_at) = :day", nativeQuery = true)
    List<Order> findByDay(LocalDate day);
}
