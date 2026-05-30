import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    health_smoke: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 20),
      maxVUs: Number(__ENV.MAX_VUS || 100),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
  const res = http.get(`${baseUrl}/api/v1/health`, {
    tags: { endpoint: 'health' },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
    'service is up': (r) => r.json('data.status') === 'UP',
  });

  sleep(0.1);
}
