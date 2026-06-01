import http from 'k6/http';
import { check, sleep } from 'k6';

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 422, 429));

export const options = {
  scenarios: {
    booking_smoke: {
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
    'http_req_duration{endpoint:checkout}': ['p(95)<200'],
    'http_req_duration{endpoint:booking}': ['p(95)<700'],
    checks: ['rate>0.95'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const userId = (__VU * 100000) + __ITER + 1;
  const checkout = http.get(`${BASE_URL}/api/v1/checkout/1`, {
    headers: { 'X-User-Id': String(userId) },
    tags: { endpoint: 'checkout' },
  });

  const checkoutOk = check(checkout, {
    'checkout is 200': (r) => r.status === 200,
    'checkout has attempt': (r) => Boolean(r.json('data.booking_attempt_id')),
  });
  if (!checkoutOk) {
    sleep(0.1);
    return;
  }

  const body = {
    sale_event_id: 1,
    product_id: 1,
    booking_attempt_id: checkout.json('data.booking_attempt_id'),
    payment_methods: [{ type: 'CREDIT_CARD', amount: 10000 }],
    total_amount: 10000,
    currency: 'KRW',
    mock_pg_scenario: __ENV.PG_SCENARIO || 'SUCCESS',
  };
  const booking = http.post(`${BASE_URL}/api/v1/bookings`, JSON.stringify(body), {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(userId),
    },
    tags: { endpoint: 'booking' },
  });

  check(booking, {
    'booking controlled response': (r) => [201, 202, 409, 422, 429].includes(r.status),
    'booking body has code': (r) => Boolean(r.json('data.business_code')),
  });

  sleep(0.1);
}
