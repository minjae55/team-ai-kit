import http from 'k6/http';
import { check } from 'k6';

const VUS = parseInt(__ENV.VUS || '10');
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    order_create: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '3m' },
  },
  thresholds: {
    'http_req_duration{name:order_create}': ['p(95)<800'],
  },
};

// 로그인 비용을 측정에서 빼려고 setup()에서 토큰을 미리 받는다
export function setup() {
  const tokens = [];
  for (let i = 1; i <= VUS; i++) {
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: `loadtest${i}@stylehub-test.com`, password: __ENV.PASSWORD, rememberMe: false }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    tokens.push(res.cookies.accessToken[0].value);
  }
  return { tokens };
}

export default function (data) {
  const accessToken = data.tokens[__VU - 1];
  const auth = { headers: { Cookie: `accessToken=${accessToken}` } };

  const cartItems = http.get(`${BASE_URL}/api/carts`, auth).json('data').filter((item) => item.cartType === 'NORMAL');
  const addresses = http.get(`${BASE_URL}/api/addresses`, auth).json('data');
  if (cartItems.length === 0 || addresses.length === 0) {
    console.error(`VU ${__VU}: 카트 또는 주소 없음`);
    return;
  }

  const res = http.post(
    `${BASE_URL}/api/buyer/orders`,
    JSON.stringify({ cartItemIds: cartItems.map((i) => i.cartItemId), addressId: addresses[0].addressId, cartType: 'NORMAL' }),
    { headers: { 'Content-Type': 'application/json', Cookie: `accessToken=${accessToken}` }, tags: { name: 'order_create' } }
  );
  const ok = check(res, { '주문 생성 성공': (r) => r.status === 200 });
  if (!ok) console.error(`order_create ${res.status} ${String(res.body).slice(0, 200)}`);
}
