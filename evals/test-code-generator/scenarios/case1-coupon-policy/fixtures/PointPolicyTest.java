package shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PointPolicyTest {
    @Test
    @DisplayName("적립률 1%: 10,000원 주문이면 100포인트")
    void earn_기본() {
        assertThat(new PointPolicy(1).earn(10_000)).isEqualTo(100);
    }
}
