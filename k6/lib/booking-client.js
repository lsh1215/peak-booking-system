import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const bookingConfirmed = new Counter('booking_confirmed_total');
export const bookingWaiting = new Counter('booking_waiting_total');
export const bookingPaymentUnknown = new Counter('booking_payment_unknown_total');
export const bookingControlledRejected = new Counter('booking_controlled_rejected_total');
export const bookingTechnicalFailures = new Counter('booking_technical_failure_total');
export const bookingTechnicalFailureRate = new Rate('booking_technical_failure_rate');
export const bookingDuplicateReplay = new Counter('booking_duplicate_replay_total');
export const bookingFlowDuration = new Trend('booking_flow_duration_ms');
export const bookingControlledRate = new Rate('booking_controlled_response_rate');

http.setResponseCallback(http.expectedStatuses(
  { min: 200, max: 299 },
  400,
  409,
  422,
  429,
  503,
));

export function baseUrl() {
  return __ENV.BASE_URL || 'http://localhost:8080';
}

export function uniqueUserId() {
  const offset = Number(__ENV.USER_OFFSET || 1000000);
  return offset + (__VU * 100000) + __ITER + 1;
}

export function checkout(userId, productId = 1) {
  const response = http.get(`${baseUrl()}/api/v1/checkout/${productId}`, {
    headers: { 'X-User-Id': String(userId) },
    tags: { endpoint: 'checkout' },
  });
  check(response, {
    'checkout controlled': (r) => r.status === 200 || isControlledOverload(r),
    'checkout has attempt or overload': (r) => isControlledOverload(r)
      || Boolean(jsonPath(r, 'data.booking_attempt_id')),
  });
  recordTechnicalFailure(response);
  return response;
}

export function book(userId, attemptId, overrides = {}) {
  const productId = Number(overrides.productId || __ENV.PRODUCT_ID || 1);
  const saleEventId = Number(overrides.saleEventId || __ENV.SALE_EVENT_ID || 1);
  const totalAmount = Number(overrides.totalAmount || __ENV.TOTAL_AMOUNT || 10000);
  const currency = overrides.currency || __ENV.CURRENCY || 'KRW';
  const pgScenario = overrides.pgScenario || __ENV.PG_SCENARIO || 'SUCCESS';
  const methods = overrides.paymentMethods || [{ type: __ENV.PAYMENT_METHOD || 'CREDIT_CARD', amount: totalAmount }];
  const startedAt = Date.now();
  const response = http.post(`${baseUrl()}/api/v1/bookings`, JSON.stringify({
    sale_event_id: saleEventId,
    product_id: productId,
    booking_attempt_id: attemptId,
    payment_methods: methods,
    total_amount: totalAmount,
    currency,
    mock_pg_scenario: pgScenario,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': String(userId),
    },
    tags: { endpoint: 'booking', pg_scenario: pgScenario },
  });
  bookingFlowDuration.add(Date.now() - startedAt);
  recordBookingOutcome(response);
  return response;
}

export function bookFreshUser(overrides = {}) {
  const userId = overrides.userId || uniqueUserId();
  const checkoutResponse = checkout(userId, overrides.productId || 1);
  const attemptId = jsonPath(checkoutResponse, 'data.booking_attempt_id');
  if (!attemptId) {
    return { userId, checkoutResponse, bookingResponse: null };
  }
  const bookingResponse = book(userId, attemptId, overrides);
  return { userId, checkoutResponse, bookingResponse };
}

export function businessCode(response) {
  return jsonPath(response, 'data.business_code') || jsonPath(response, 'message') || 'NO_BODY';
}

export function recordBookingOutcome(response) {
  const technicalFailure = recordTechnicalFailure(response);
  const code = businessCode(response);
  const controlled = [201, 202, 409, 422, 429].includes(response.status) || isControlledOverload(response);
  bookingControlledRate.add(controlled);
  if (!controlled) {
    if (!technicalFailure) {
      bookingTechnicalFailures.add(1, { reason: 'unexpected_status', status: String(response.status), code });
      bookingTechnicalFailureRate.add(true, { reason: 'unexpected_status' });
    }
    return;
  }
  if (response.status === 201 || code === 'BOOKING_CONFIRMED') {
    bookingConfirmed.add(1);
    return;
  }
  if (code === 'WAITING_CANDIDATE' || code === 'BOOKING_IN_PROGRESS') {
    bookingWaiting.add(1);
    return;
  }
  if (code === 'PAYMENT_UNKNOWN') {
    bookingPaymentUnknown.add(1);
    return;
  }
  if ([409, 422, 429].includes(response.status)) {
    bookingControlledRejected.add(1, { code });
  }
}

export function recordDuplicateReplay(first, second) {
  const firstCode = businessCode(first);
  const secondCode = businessCode(second);
  if (first.status === second.status && firstCode === secondCode) {
    bookingDuplicateReplay.add(1);
  }
}

export function jsonPath(response, path) {
  try {
    return response.json(path);
  } catch (ignored) {
    return null;
  }
}

function recordTechnicalFailure(response) {
  const failed = !isControlledOverload(response)
    && (Boolean(response.error) || response.status === 0 || response.status >= 500);
  bookingTechnicalFailureRate.add(failed);
  if (failed) {
    bookingTechnicalFailures.add(1, { status: String(response.status || 0) });
  }
  return failed;
}

export function isControlledOverload(response) {
  const message = jsonPath(response, 'message') || '';
  return (response.status === 503 && message === 'Service is temporarily busy')
    || (response.status === 429 && message.includes('busy'));
}
