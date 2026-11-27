# k6 스크립트 골격과 패턴

## 기본 골격 (목표 동시 사용자에서 응답 시간)
```js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';   // 프로젝트 로컬 주소
const VUS = parseInt(__ENV.VUS || '10');
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    main: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  },
  thresholds: {
    'http_req_duration{name:target}': ['p(95)<500'],   // 요청한 목표
    'checks': ['rate>0.99'],
  },
};

export function setup() {
  // 로그인 비용을 측정에서 빼려고 토큰을 미리 받는다
  const tokens = [];
  for (let i = 1; i <= VUS; i++) {
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: `loadtest${i}@example.com`, password: __ENV.PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } });
    check(res, { 'login 200': (r) => r.status === 200 });
    tokens.push(res.json('accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const res = http.get(`${BASE_URL}/api/target`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'target' },
  });
  const ok = check(res, { 'status 200': (r) => r.status === 200 });
  if (!ok) console.error(`target ${res.status} ${String(res.body).slice(0, 200)}`);
  sleep(1);
}
```

## 경쟁 상태 재현 (VU마다 1회, 동시에)
```js
export const options = {
  scenarios: { race: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' } },
};
```
- 같은 자원(같은 재고 옵션 등)에 몰리게 하고, 끝난 뒤 성공 수와 남은 재고를 비교할 수 있게 `console.log`로 결과를 남긴다.

## 목표 처리량
```js
scenarios: { rps: { executor: 'constant-arrival-rate', rate: parseInt(__ENV.RPS || '50'), timeUnit: '1s',
                    duration: DURATION, preAllocatedVUs: VUS, maxVUs: VUS * 2 } }
```

## 전후 비교용으로 쓸 때
- 조건(VU, 시간, 데이터, 워밍업)을 고정하고 `--summary-export`로 결과를 파일에 남긴다.
- 캐시가 있는 API는 워밍업 구간과 측정 구간을 나눈다 (시나리오 2개, `startTime`).
