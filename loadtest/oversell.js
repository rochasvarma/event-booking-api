// Correctness under concurrency: many buyers race for TICKETS tickets of the same event.
// PASS = tickets sold never exceed capacity, and every successful (201) booking maps to exactly
// one ticket taken (sold == number of 201s).
//
//   k6 run -e BASE_URL=https://your-app.onrender.com loadtest/oversell.js
//
// VUS concurrent buyers share ATTEMPTS booking attempts (default 100 buyers x 300 attempts for
// 100 tickets). Reusing keep-alive connections avoids opening hundreds of TLS handshakes in the
// same instant, which free-tier edge proxies reset before they ever reach the app.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 100);
const ATTEMPTS = Number(__ENV.ATTEMPTS || 300);
const TICKETS = Number(__ENV.TICKETS || 100);
const H = { 'Content-Type': 'application/json' };
const ok = new Counter('bookings_ok');
const soldOut = new Counter('bookings_sold_out');
const other = new Counter('bookings_other_error');

export const options = {
  setupTimeout: '5m',
  scenarios: { race: { executor: 'shared-iterations', vus: VUS, iterations: ATTEMPTS, maxDuration: '3m' } },
};

const post = (p, b, t) => http.post(`${BASE}${p}`, JSON.stringify(b),
    { headers: t ? { ...H, Authorization: `Bearer ${t}` } : H, timeout: '60s' });

export function setup() {
  const run = Date.now();
  const org = post('/api/auth/register', { name: 'Org', email: `o-${run}@loadtest.local`, password: 'secret123', role: 'ORGANIZER' }).json('token');
  const ev = post('/api/events', { title: `Race ${run}`, venue: 'Arena', startsAt: new Date(Date.now() + 864e5).toISOString(), totalTickets: TICKETS }, org).json('id');
  const cust = post('/api/auth/register', { name: 'Racer', email: `r-${run}@loadtest.local`, password: 'secret123', role: 'CUSTOMER' }).json('token');
  return { org, ev, cust };
}

export default function (d) {
  const r = post(`/api/events/${d.ev}/bookings`, { quantity: 1 }, d.cust);
  if (r.status === 201) ok.add(1);
  else if (r.status === 409) soldOut.add(1);
  else other.add(1);
}

export function teardown(d) {
  const ev = http.get(`${BASE}/api/events/${d.ev}`).json();
  const sold = ev.totalTickets - ev.availableTickets;
  const verdict = sold > TICKETS ? 'FAIL - OVERSOLD' : 'PASS - no overselling';
  console.log(`\n${verdict}: ${ATTEMPTS} attempts by ${VUS} concurrent buyers for ${TICKETS} tickets -> ` +
      `sold=${sold}, available=${ev.availableTickets}. Compare sold with bookings_ok below (they must be equal).\n`);
}