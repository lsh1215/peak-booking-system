import http from 'k6/http';
import { check } from 'k6';

http.setResponseCallback(http.expectedStatuses(
  { min: 200, max: 299 },
  429,
  503,
));

const rate = Number(__ENV.RATE || 1000);
const duration = __ENV.DURATION || '2m';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    checkout: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || Math.max(10, Math.ceil(rate / 2))),
      maxVUs: Number(__ENV.MAX_VUS || Math.max(rate * 2, 100)),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const userId = Number(__ENV.USER_OFFSET || 50000000) + (__VU * 100000) + __ITER + 1;
  const response = http.get(`${baseUrl}/api/v1/checkout/1`, {
    headers: { 'X-User-Id': String(userId) },
    tags: { endpoint: 'checkout' },
  });
  check(response, {
    'checkout controlled': (r) => r.status === 200 || r.status === 429 || r.status === 503,
    'checkout has attempt or overload': (r) => {
      if (r.status === 429 || r.status === 503) {
        return true;
      }
      try {
        return Boolean(r.json('data.booking_attempt_id'));
      } catch (ignored) {
        return false;
      }
    },
  });
}

export function handleSummary(data) {
  const summary = {
    http_reqs: metricValue(data, 'http_reqs', 'count', 0),
    iterations: metricValue(data, 'iterations', 'count', 0),
    dropped_iterations: metricValue(data, 'dropped_iterations', 'count', 0),
    http_failed_rate: metricValue(data, 'http_req_failed', 'rate', 0),
    checks_rate: metricValue(data, 'checks', 'rate', 0),
    checkout_p95: metricValue(data, 'http_req_duration{endpoint:checkout}', 'p(95)', null),
    checkout_avg: metricValue(data, 'http_req_duration{endpoint:checkout}', 'avg', null),
  };
  const summaryPath = __ENV.SUMMARY_EXPORT;
  const output = {
    stdout: `${JSON.stringify(summary, null, 2)}\n`,
  };
  if (summaryPath) {
    output[summaryPath] = JSON.stringify(data, null, 2);
  }
  return output;
}

function metricValue(data, metricName, valueName, fallback) {
  const metric = data.metrics[metricName];
  if (!metric || !metric.values || metric.values[valueName] === undefined) {
    return fallback;
  }
  return metric.values[valueName];
}
