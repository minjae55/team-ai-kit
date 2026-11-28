package shop;

import java.time.LocalDate;

/**
 * 쿠폰 할인 정책 (명세)
 * - 주문 금액이 음수면 IllegalArgumentException
 * - 오늘이 만료일(expiresOn)보다 뒤면 IllegalStateException. 만료일 당일은 사용 가능
 * - 주문 금액이 최소 주문 금액(minOrder) "이상"일 때만 할인. 미만이면 할인 0
 * - 할인액 = 주문 금액 × rate(%) / 100 (원 단위 버림). 단 maxDiscount를 넘지 않는다
 */
public class CouponPolicy {
    private final int rate;
    private final long minOrder;
    private final long maxDiscount;
    private final LocalDate expiresOn;

    public CouponPolicy(int rate, long minOrder, long maxDiscount, LocalDate expiresOn) {
        this.rate = rate;
        this.minOrder = minOrder;
        this.maxDiscount = maxDiscount;
        this.expiresOn = expiresOn;
    }

    public long discount(long orderAmount, LocalDate today) {
        if (orderAmount < 0) {
            throw new IllegalArgumentException("주문 금액은 0 이상이어야 합니다");
        }
        if (today.isAfter(expiresOn)) {
            throw new IllegalStateException("만료된 쿠폰입니다");
        }
        if (orderAmount > minOrder) {
            return Math.min(orderAmount * rate / 100, maxDiscount);
        }
        return 0;
    }
}
