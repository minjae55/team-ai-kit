// eval 케이스 3: 구매자 주문 목록 조회(GET /api/buyer/orders) 부하. 주문이 많은 구매자 20명
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = 'http://localhost:8080';
const EMAILS = [4, 0, 2, 3, 5, 14, 28, 21, 39, 32, 22, 31, 6, 19, 17, 38, 8, 29, 9, 12]
  .map((n) => `user${n}@stylehub-test.com`);

export const options = {
  vus: parseInt(__ENV.VUS || '5'),
  duration: __ENV.DURATION || '60s',
};

export function setup() {
  return {
    tokens: EMAILS.map((email) => {
      const res = http.post(`${BASE_URL}/api/auth/login`,
        JSON.stringify({ email, password: 'Password1234!', rememberMe: false }),
        { headers: { 'Content-Type': 'application/json' } });
      return res.cookies.accessToken[0].value;
    }),
  };
}

export default function (data) {
  const token = data.tokens[(__VU + __ITER) % data.tokens.length];
  const res = http.get(`${BASE_URL}/api/buyer/orders`, {
    headers: { Cookie: `accessToken=${token}` },
    tags: { name: 'buyer_order_list' },
    timeout: '60s',
  });
  check(res, { '200': (r) => r.status === 200 });
  sleep(1);
}
