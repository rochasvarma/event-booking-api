// Correctness under concurrency: VUS customers race for TICKETS tickets at the same instant.
// PASS = exactly TICKETS bookings succeed and available_tickets ends at 0 (no overselling).
//
//   k6 run -e BASE_URL=https://your-app.onrender.com -e VUS=300 -e TICKETS=100 loadtest/oversell.js
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 300);
const TICKETS = Number(__ENV.TICKETS || 100);
const H = { 'Content-Type': 'application/json' };
const ok = new Counter('bookings_ok');
const rejected = new Counter('bookings_rejected');

export const options = {
  setupTimeout: '5m',
  scenarios: { race: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' } },
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
  if (r.status === 201) ok.add(1); else rejected.add(1);
}

export function teardown(d) {
  const ev = http.get(`${BASE}/api/events/${d.ev}`).json();
  const sold = ev.totalTickets - ev.availableTickets;
  const pass = sold === TICKETS && ev.availableTickets === 0;
  console.log(`\n${pass ? 'PASS' : 'FAIL'}: ${VUS} buyers, ${TICKETS} tickets -> sold=${sold}, available=${ev.availableTickets}\n`);
}
