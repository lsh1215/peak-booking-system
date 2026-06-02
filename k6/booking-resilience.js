import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import {
  baseUrl,
  book,
  bookFreshUser,
  bookingControlledRate,
  bookingTechnicalFailures,
  businessCode,
  checkout,
  isControlledOverload,
  jsonPath,
  recordDuplicateReplay,
  uniqueUserId,
} from './lib/booking-client.js';

const scenario = (__ENV.SCENARIO || 'booking').toLowerCase();
const scenarioKind = canonicalScenario(scenario);
const rate = Number(__ENV.RATE || defaultRate(scenario, scenarioKind));
const duration = __ENV.DURATION || defaultDuration(scenarioKind);
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || Math.max(10, Math.ceil(rate / 2)));
const maxVUs = Number(__ENV.MAX_VUS || Math.max(preAllocatedVUs * 4, rate * 2));

export const options = {
  scenarios: {
    [scenario]: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: thresholdsFor(scenarioKind),
};

export default function () {
  switch (scenarioKind) {
    case 'health':
      health();
      return;
    case 'checkout':
      checkout(uniqueUserId());
      return;
    case 'duplicate':
      duplicateClick();
      return;
    case 'pg-timeout':
      bookFreshUser({ pgScenario: __ENV.PG_SCENARIO || 'TIMEOUT' });
      return;
    case 'late-success':
      bookFreshUser({ pgScenario: 'LATE_SUCCESS' });
      return;
    case 'payment-failure':
      bookFreshUser({ pgScenario: 'FAILURE' });
      return;
    case 'realistic-mixed':
    case 'adversarial-mixed':
    case 'mixed':
      mixed();
      return;
    case 'redis-down':
    case 'was-one-down':
    case 'booking':
    case 'peak':
    default:
      bookFreshUser({ pgScenario: __ENV.PG_SCENARIO || 'SUCCESS' });
  }
  sleep(Number(__ENV.SLEEP_SECONDS || 0.05));
}

function health() {
  const response = http.get(`${baseUrl()}/api/v1/health`, {
    tags: { endpoint: 'health' },
  });
  check(response, {
    'health 200': (r) => r.status === 200,
    'health UP': (r) => jsonPath(r, 'data.status') === 'UP',
  });
}

function duplicateClick() {
  const userId = uniqueUserId();
  const checkoutResponse = checkout(userId);
  const attemptId = jsonPath(checkoutResponse, 'data.booking_attempt_id');
  if (!attemptId) {
    if (!isControlledOverload(checkoutResponse)) {
      bookingTechnicalFailures.add(1, { reason: 'missing_attempt' });
    }
    return;
  }
  const first = book(userId, attemptId, { pgScenario: __ENV.PG_SCENARIO || 'SUCCESS' });
  const second = book(userId, attemptId, { pgScenario: __ENV.PG_SCENARIO || 'SUCCESS' });
  recordDuplicateReplay(first, second);
  followWaitingCandidate({ userId, checkoutResponse, bookingResponse: second }, __ENV.PG_SCENARIO || 'SUCCESS');
  check(second, {
    'duplicate controlled': (r) => [201, 202, 409, 422, 429].includes(r.status),
    'duplicate did not become 5xx': (r) => r.status < 500 && r.status !== 0,
  });
}

function mixed() {
  const bucket = exec.scenario.iterationInTest % 100;
  const defaults = mixedDefaults(scenarioKind);
  const duplicatePercent = Number(__ENV.MIXED_DUPLICATE_PERCENT || defaults.duplicate);
  const timeoutPercent = Number(__ENV.MIXED_TIMEOUT_PERCENT || defaults.timeout);
  const failurePercent = Number(__ENV.MIXED_FAILURE_PERCENT || defaults.failure);
  if (bucket < duplicatePercent) {
    duplicateClick();
    return;
  }
  let result;
  if (bucket < duplicatePercent + timeoutPercent) {
    result = bookFreshUser({ pgScenario: 'TIMEOUT' });
    followWaitingCandidate(result, 'TIMEOUT');
    return;
  }
  if (bucket < duplicatePercent + timeoutPercent + failurePercent) {
    result = bookFreshUser({ pgScenario: 'FAILURE' });
    followWaitingCandidate(result, 'FAILURE');
    return;
  }
  result = bookFreshUser({ pgScenario: 'SUCCESS' });
  followWaitingCandidate(result, 'SUCCESS');
}

function followWaitingCandidate(result, pgScenario) {
  if (!result || !result.bookingResponse) {
    return;
  }
  const code = businessCode(result.bookingResponse);
  if (code !== 'WAITING_CANDIDATE' && code !== 'BOOKING_IN_PROGRESS') {
    return;
  }
  const attemptId = jsonPath(result.checkoutResponse, 'data.booking_attempt_id');
  if (!attemptId) {
    return;
  }
  const delaySeconds = Number(__ENV.WAITING_FOLLOWUP_DELAY_SECONDS || 5);
  const attempts = Number(__ENV.WAITING_FOLLOWUP_ATTEMPTS || 12);
  for (let i = 0; i < attempts; i += 1) {
    sleep(i === 0 ? delaySeconds : Number(__ENV.WAITING_FOLLOWUP_RETRY_DELAY_SECONDS || 5));
    const replay = book(result.userId, attemptId, { pgScenario });
    const replayCode = businessCode(replay);
    if (replayCode !== 'WAITING_CANDIDATE' && replayCode !== 'BOOKING_IN_PROGRESS') {
      return;
    }
  }
}

function canonicalScenario(name) {
  switch (name) {
    case 'duplicate-click':
      return 'duplicate';
    case 'mixed-realistic':
      return 'realistic-mixed';
    case 'mixed-adversarial':
      return 'adversarial-mixed';
    case 'peak-500':
    case 'peak-1000':
      return 'peak';
    default:
      return name;
  }
}

function mixedDefaults(name) {
  switch (name) {
    case 'realistic-mixed':
      return { duplicate: 20, timeout: 2, failure: 8 };
    case 'adversarial-mixed':
      return { duplicate: 30, timeout: 40, failure: 10 };
    default:
      return { duplicate: 20, timeout: 10, failure: 10 };
  }
}

function defaultRate(name, kind) {
  switch (name) {
    case 'peak-1000':
      return 1000;
    case 'peak-500':
      return 500;
    default:
      break;
  }
  switch (kind) {
    case 'health':
      return 20;
    case 'checkout':
      return 1000;
    case 'duplicate':
      return 50;
    case 'peak':
      return 500;
    case 'redis-down':
    case 'was-one-down':
    case 'pg-timeout':
    case 'payment-failure':
    case 'late-success':
    case 'realistic-mixed':
    case 'adversarial-mixed':
    case 'mixed':
      return 100;
    default:
      return 50;
  }
}

function defaultDuration(name) {
  switch (name) {
    case 'health':
      return '30s';
    case 'checkout':
      return '1m';
    case 'peak':
      return '1m';
    default:
      return '45s';
  }
}

function thresholdsFor(name) {
  const common = {
    http_req_failed: ['rate<0.01'],
    booking_controlled_response_rate: ['rate>0.98'],
    booking_technical_failure_rate: ['rate<0.01'],
    checks: ['rate>0.95'],
    dropped_iterations: [`count<${allowedDroppedIterations(name)}`],
  };
  if (name === 'health') {
    return {
      http_req_failed: ['rate<0.01'],
      'http_req_duration{endpoint:health}': ['p(95)<500'],
      checks: ['rate>0.99'],
      dropped_iterations: ['count<1'],
    };
  }
  if (name === 'checkout') {
    return {
      http_req_failed: ['rate<0.01'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
      checks: ['rate>0.95'],
      dropped_iterations: [`count<${allowedDroppedIterations(name)}`],
    };
  }
  if (name === 'peak') {
    return {
      ...common,
      'http_req_duration{endpoint:booking}': ['p(95)<400'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    };
  }
  if (name === 'redis-down') {
    return {
      ...common,
      dropped_iterations: [`count<${allowedDroppedIterations(name, 0.03)}`],
      'http_req_duration{endpoint:booking}': ['p(95)<1500'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    };
  }
  if (name === 'pg-timeout' || name === 'late-success') {
    return {
      ...common,
      'http_req_duration{endpoint:booking}': ['p(95)<700'],
    };
  }
  if (name === 'payment-failure') {
    return {
      ...common,
      'http_req_duration{endpoint:booking}': ['p(95)<700'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    };
  }
  if (name === 'adversarial-mixed') {
    return {
      ...common,
      dropped_iterations: [`count<${allowedDroppedIterations(name, 0.03)}`],
      'http_req_duration{endpoint:booking}': ['p(95)<1500'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    };
  }
  if (name === 'mixed' || name === 'realistic-mixed') {
    return {
      ...common,
      'http_req_duration{endpoint:booking}': ['p(95)<500'],
      'http_req_duration{endpoint:checkout}': ['p(95)<350'],
    };
  }
  return {
    ...common,
    'http_req_duration{endpoint:booking}': ['p(95)<400'],
    'http_req_duration{endpoint:checkout}': ['p(95)<250'],
  };
}

function allowedDroppedIterations(name, ratio = 0.01) {
  const expected = Math.max(1, Math.ceil(rate * parseDurationSeconds(duration)));
  return Math.max(name === 'health' ? 1 : 5, Math.ceil(expected * ratio));
}

function parseDurationSeconds(value) {
  const match = String(value).trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)$/);
  if (!match) {
    return 60;
  }
  const amount = Number(match[1]);
  switch (match[2]) {
    case 'ms':
      return amount / 1000;
    case 's':
      return amount;
    case 'm':
      return amount * 60;
    case 'h':
      return amount * 3600;
    default:
      return 60;
  }
}

export function handleSummary(data) {
  const output = {};
  if (__ENV.SUMMARY_EXPORT) {
    output[__ENV.SUMMARY_EXPORT] = JSON.stringify(data, null, 2);
  }
  output.stdout = [
    '',
    'Peak booking k6 summary',
    `scenario=${scenario}`,
    `scenario_kind=${scenarioKind}`,
    `rate=${rate}/s duration=${duration}`,
    `confirmed_responses=${metricCount(data, 'booking_confirmed_response_total')}`,
    `controlled_rejected=${metricCount(data, 'booking_controlled_rejected_total')}`,
    `payment_unknown=${metricCount(data, 'booking_payment_unknown_total')}`,
    `waiting=${metricCount(data, 'booking_waiting_total')}`,
    `technical_failures=${metricCount(data, 'booking_technical_failure_total')}`,
    '',
  ].join('\n');
  return output;
}

function metricCount(data, name) {
  const metric = data.metrics[name];
  if (!metric || !metric.values || metric.values.count === undefined) {
    return 0;
  }
  return metric.values.count;
}
