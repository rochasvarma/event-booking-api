// k6 stress test: N concurrent customers hammering POST /api/events/{id}/bookings
// (optionally while an organizer keeps updating the same event).
//
//   k6 run -e BASE_URL=https://your-app.onrender.com -e VUS=100 -e DURATION=30s loadtest/booking.js
//
// Env:
//   BASE_URL     API base URL (default http://localhost:8080)
//   VUS          concurrent virtual users (default 50)
//   DURATION     how long to hold that load (default 30s)
//   CUSTOMERS    distinct customer accounts the VUs share (default 50)
//   UPDATE_RPS   event updates per second by the organizer during the test (default 0 = off)
//   TIMEOUT      per-request timeout before counting it as dropped (default 10s)
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '30s';
const CUSTOMERS = Number(__ENV.CUSTOMERS || 50);
const UPDATE_RPS = Number(__ENV.UPDATE_RPS || 0);
const TIMEOUT = __ENV.TIMEOUT || '10s';
const JSON_HDR = { 'Content-Type': 'application/json' };

const booked = new Counter('bookings_ok');          // 201
const soldOut = new Counter('bookings_conflict');   // 409
const shed = new Counter('bookings_503');           // 503 server busy (load shed)
const dropped = new Counter('bookings_dropped');    // timeouts / connection errors / 5xx other than 503
const failed = new Rate('booking_failed');          // anything but 201
const latency = new Trend('booking_latency', true);

const scenarios = {
  bookings: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'book' },
};
if (UPDATE_RPS > 0) {
  scenarios.updates = {
    executor: 'constant-arrival-rate', rate: UPDATE_RPS, timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: 5, exec: 'update',
  };
}
export const options = { scenarios, setupTimeout: '5m', discardResponseBodies: false };

function post(path, body, token) {
  const headers = token ? { ...JSON_HDR, Authorization: `Bearer ${token}` } : JSON_HDR;
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers, timeout: '60s' });
}

export function setup() {
  const run = Date.now();
  const org = post('/api/auth/register', {
    name: 'Load Organizer', email: `org-${run}@loadtest.local`, password: 'secret123', role: 'ORGANIZER',
  });
  if (org.status !== 201) throw new Error(`organizer register failed: ${org.status} ${org.body}`);
  const orgToken = org.json('token');

  const ev = post('/api/events', {
    title: `Load test ${run}`, venue: 'Stress Arena',
    startsAt: new Date(Date.now() + 30 * 864e5).toISOString(), totalTickets: 1000000,
  }, orgToken);
  if (ev.status !== 201) throw new Error(`event create failed: ${ev.status} ${ev.body}`);

  const tokens = [];
  for (let i = 0; i < CUSTOMERS; i += 10) {
    const reqs = [];
    for (let j = i; j < Math.min(i + 10, CUSTOMERS); j++) {
      reqs.push(['POST', `${BASE}/api/auth/register`, JSON.stringify({
        name: `Customer ${j}`, email: `c${j}-${run}@loadtest.local`, password: 'secret123', role: 'CUSTOMER',
      }), { headers: JSON_HDR, timeout: '60s' }]);
    }
    for (const r of http.batch(reqs)) {
      if (r.status === 201) tokens.push(r.json('token'));
    }
  }
  if (tokens.length === 0) throw new Error('no customers registered');
  console.log(`setup: event ${ev.json('id')}, ${tokens.length} customers`);
  return { eventId: ev.json('id'), orgToken, tokens };
}

export function book(data) {
  const token = data.tokens[(__VU + __ITER) % data.tokens.length];
  const res = http.post(`${BASE}/api/events/${data.eventId}/bookings`, JSON.stringify({ quantity: 1 }), {
    headers: { ...JSON_HDR, Authorization: `Bearer ${token}` }, timeout: TIMEOUT, tags: { name: 'book' },
  });
  latency.add(res.timings.duration);
  const ok = res.status === 201;
  failed.add(!ok);
  if (ok) booked.add(1);
  else if (res.status === 409) soldOut.add(1);
  else if (res.status === 503) shed.add(1);
  else dropped.add(1);
  check(res, { 'booked (201)': (r) => r.status === 201 });
}

export function update(data) {
  const res = http.patch(`${BASE}/api/events/${data.eventId}`,
    JSON.stringify({ description: `updated at ${new Date().toISOString()}` }),
    { headers: { ...JSON_HDR, Authorization: `Bearer ${data.orgToken}` }, timeout: TIMEOUT, tags: { name: 'update' } });
  check(res, { 'updated (200)': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (k, f) => (m[k] && m[k].values[f] !== undefined ? m[k].values[f] : 0);
  const total = v('bookings_ok', 'count') + v('bookings_conflict', 'count') + v('bookings_503', 'count') + v('bookings_dropped', 'count');
  const secs = data.state.testRunDurationMs / 1000;
  const line = [
    `VUS=${VUS}`,
    `requests=${total}`,
    `ok=${v('bookings_ok', 'count')}`,
    `throughput=${(v('bookings_ok', 'count') / secs).toFixed(1)}/s`,
    `p50=${v('booking_latency', 'med').toFixed(0)}ms`,
    `p95=${v('booking_latency', 'p(95)').toFixed(0)}ms`,
    `503=${v('bookings_503', 'count')}`,
    `dropped=${v('bookings_dropped', 'count')}`,
    `errorRate=${(v('booking_failed', 'rate') * 100).toFixed(2)}%`,
  ].join('  ');
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }) + `\n\nRESULT  ${line}\n`,
    [`loadtest/results/vus-${VUS}.txt`]: line + '\n',
  };
}
