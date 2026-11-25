// eval 케이스 2 준비: oversell-test.js의 20명 장바구니에 옵션 120001을 앱 API로 담는다
import http from 'k6/http';
import { check } from 'k6';

export const options = { vus: 1, iterations: 1 };

const BASE_URL = 'http://localhost:8080';
const OPTION_ID = 120001;
const QUANTITY = parseInt(__ENV.QTY || '37'); // 상품 MOQ
const EMAILS = [0, 1, 2, 6, 7, 8, 14, 16, 17, 22, 24, 26, 31, 32, 33, 34, 35, 37, 40, 45]
  .map((n) => `user${n}@stylehub-test.com`);

export default function () {
  for (const email of EMAILS) {
    const login = http.post(`${BASE_URL}/api/auth/login`,
      JSON.stringify({ email, password: 'Password1234!', rememberMe: false }),
      { headers: { 'Content-Type': 'application/json' } });
    const token = login.cookies.accessToken[0].value;
    const res = http.post(`${BASE_URL}/api/carts`,
      JSON.stringify({ productOptionId: OPTION_ID, quantity: QUANTITY, cartType: 'NORMAL' }),
      { headers: { 'Content-Type': 'application/json', Cookie: `accessToken=${token}` } });
    check(res, { 'cart add 200': (r) => r.status === 200 });
    if (res.status !== 200) console.error(`${email}: ${res.status} ${res.body}`);
  }
}
