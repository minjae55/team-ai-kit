// eval 케이스 1: 커넥션 풀 고갈 재현용 (sleep 없이 계속 조회)
import http from 'k6/http';
import { check } from 'k6';

const VUS = parseInt(__ENV.VUS || '300');
const BASE_URL = 'http://localhost:8080';

export const options = {
  vus: VUS,
  duration: __ENV.DURATION || '60s',
  setupTimeout: '600s',
};

export function setup() {
  const tokens = [];
  for (let i = 0; i < Math.min(VUS, 500); i++) {
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: `user${i}@stylehub-test.com`, password: 'Password1234!', rememberMe: false }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    tokens.push(res.cookies.accessToken[0].value);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.get(`${BASE_URL}/api/negotiations?page=${__ITER % 20}`, {
    headers: { Cookie: `accessToken=${token}` },
    tags: { name: 'negotiation_list' },
  });
  check(res, { '200': (r) => r.status === 200 });
}
