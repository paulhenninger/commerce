#!/usr/bin/env python3
"""Proves, against a running DEV stack, that the testkit does what architecture
section 11 says, and that the system contains every problem actor.

    scripts/stack.sh dev up -d --wait && scripts/verify-testkit.py

1. Switching test data on makes the stack a shop: seeded products with stock,
   and the administrator and shopper@example.com can sign in. (DEV's
   simulations are off until switched on, ADR-033; this script switches on what
   it needs and puts test data back as it found it.)
2. Twenty simulated shoppers sustain orders, cancellations and returns.
3. Ten problem actors run at once (bad producers, the intruder, bad consumers)
   and each produces its expected response — while scripts/smoke.sh still passes.
4. A flood raises lag and the lag recovers while the shop keeps answering; a
   stuck consumer is evicted from its own group. (The flood, 400/s for 30 s, is
   sized to what one replica drains in a couple of minutes: each event is its own
   transaction, about 120 a second per service on Docker Desktop.)
5. No real service wrote an event that breaks its contract.

Everything goes through the ops console's API (and so its testkit proxy), as an
operator would. Takes about eight minutes.
"""
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
SHOP = f"http://localhost:{ENV.get('STOREFRONT_PORT', '8081')}"
CONSOLE = f"http://localhost:{ENV.get('OPS_CONSOLE_PORT', '8082')}"
PROMETHEUS = f"http://localhost:{ENV.get('PROMETHEUS_PORT', '9090')}"
ORDER, INVENTORY, STOCK = 'commerce.order.events.v1', 'commerce.inventory.events.v1', 'commerce.inventory.stock.v1'
BILLING, SHIPPING = 'commerce.billing.events.v1', 'commerce.shipping.events.v1'
passed = 0
token = None
test_data_before = None


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:3000], file=sys.stderr)
    try:
        call('DELETE', '/api/v1/ops/testkit/actors')
        call('PUT', '/api/v1/ops/testkit/shoppers', settings(0, 0))
        if test_data_before is False:
            call('PUT', '/api/v1/ops/testkit/test-data', {'on': False})
    except Exception:  # noqa: BLE001 — best effort clean-up
        pass
    sys.exit(1)


def call(method, path, body=None, base=CONSOLE, auth=True):
    request = urllib.request.Request(base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if auth and token else {})})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw[:1] in (b'[', b'{') else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw[:1] in (b'[', b'{') else raw.decode(errors='replace')


def prom(query):
    with urllib.request.urlopen(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode({'query': query}),
                                timeout=20) as r:
        result = json.loads(r.read())['data']['result']
    return float(result[0]['value'][1]) if result else 0.0


def settings(count, opm, cancel=0.2, ret=0.3, decline=0.1, oos=0.05):
    return {'count': count, 'ordersPerMinute': opm, 'cancelRate': cancel, 'returnRate': ret,
            'declineRate': decline, 'outOfStockRate': oos}


def status():
    code, body = call('GET', '/api/v1/ops/testkit/status')
    if code != 200:
        fail('the testkit answers through the console', f'{code} {body}')
    return body


def wait(name, check, timeout, every=3):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = check()
        if value:
            return value
        time.sleep(every)
    fail(name, f'not true after {timeout} s')


def bash():
    """Git Bash on Windows (plain 'bash' there can be WSL's, which cannot reach Docker Desktop)."""
    if sys.platform == 'win32':
        for candidate in (r'C:\Program Files\Git\bin\bash.exe', r'C:\Program Files (x86)\Git\bin\bash.exe'):
            if os.path.exists(candidate):
                return candidate
    return 'bash'


def actor(view_id):
    return next((a for a in status()['actors'] if a['id'] == view_id), None)


def counts(view_id):
    a = actor(view_id)
    return a['counts'] if a else {}


print('Test data makes the stack a shop')
code, login = call('POST', '/api/v1/auth/login', {'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                  'password': ENV['DEV_ADMIN_PASSWORD']})
if code != 200:
    fail('the administrator (made at startup) signs in to the console', f'{code} {login}')
token = login['accessToken']
test_data_before = status()['testData']
call('PUT', '/api/v1/ops/testkit/test-data', {'on': True})
wait('the testkit seeds the test data once it is switched on', lambda: status()['seeded'], 300)
ok('test data switched on and seeded, through the console')
code, catalog = call('GET', '/api/v1/catalog/products?size=50', base=SHOP, auth=False)
items = catalog['items'] if code == 200 else []
if len(items) < 24:
    fail('the catalog has the seeded products', f'{code} {len(items)}')
ok(f'the catalog has the seeded products ({len(items)})')
in_stock = [p for p in items if p['available'] > 0]
if len(in_stock) < 23:
    fail('they are in stock (all but the sold-out one)', f'{len(in_stock)} in stock')
ok(f'they are in stock, all but the sold-out one ({len(in_stock)})')
for email, password in ((ENV['BOOTSTRAP_ADMIN_EMAIL'], ENV['DEV_ADMIN_PASSWORD']),
                        ('shopper@example.com', ENV['DEV_SHOPPER_PASSWORD'])):
    code, login = call('POST', '/api/v1/auth/login', {'email': email, 'password': password}, base=SHOP)
    if code != 200:
        fail(f'{email} can sign in', f'{code} {login}')
    ok(f'{email} can sign in')

print('Simulated shoppers')
started = time.time()
code, _ = call('PUT', '/api/v1/ops/testkit/shoppers', settings(20, 120))
if code != 200:
    fail('shoppers can be started from the console', str(code))
wait('20 shoppers sign up through the real flow', lambda: status()['shoppers']['ready'] >= 20, 240)
ok(f'20 shoppers signed up through the real flow ({time.time() - started:.0f} s)')

print('Problem actors, all at once, while the smoke test runs')
plan = [('producer', 'MALFORMED', ORDER, 20), ('producer', 'INVALID_SCHEMA', INVENTORY, 10),
        ('producer', 'UNKNOWN_TYPE', BILLING, 10), ('producer', 'DUPLICATE', SHIPPING, 10),
        ('producer', 'ORPHAN', STOCK, 10), ('producer', 'OVERSIZED', ORDER, 1), ('producer', 'UNAUTHORISED', ORDER, 1),
        ('consumer', 'SLOW', ORDER, 1), ('consumer', 'CRASHING', ORDER, 1), ('consumer', 'FLAPPING', ORDER, 1)]
before = {
    'invalid_order': prom(f'sum(commerce_events_dead_lettered_total{{reason="invalid", topic="{ORDER}"}})'),
    'invalid_inventory': prom(f'sum(commerce_events_dead_lettered_total{{reason="invalid", topic="{INVENTORY}"}})'),
    'unhandled': prom('sum(commerce_events_consumed_total{consumer="order", type="unhandled"})'),
    'duplicate': prom('sum(commerce_events_consumed_total{type="shipment.return-received", outcome="duplicate"})'),
    'orphan': prom('sum(commerce_events_consumed_total{consumer="catalog", type="inventory.stock-changed", outcome="ignored"})'),
}
ids = {}
for kind, kind_type, topic, rate in plan:
    code, view = call('POST', '/api/v1/ops/testkit/actors', {'kind': kind, 'type': kind_type, 'topic': topic,
                                                              'ratePerSecond': rate})
    if code != 201:
        fail(f'the {kind_type} actor starts', f'{code} {view}')
    ids[kind_type] = view['id']
ok(f'ten actors started: {", ".join(ids)}')
smoke = subprocess.run([bash(), str(ROOT / 'scripts/smoke.sh')], capture_output=True, text=True)
tail = (smoke.stdout + smoke.stderr).strip().splitlines()[-3:]
if smoke.returncode != 0:
    fail('the smoke test passes while they run', '\n'.join(tail))
ok(f'the smoke test passes while they run ({tail[-1].strip()})')
time.sleep(20)  # one more scrape and evaluation after the last records

checks = [
    ('MALFORMED records are dead-lettered (invalid, no retry)',
     lambda: prom(f'sum(commerce_events_dead_lettered_total{{reason="invalid", topic="{ORDER}"}})') > before['invalid_order']),
    ('INVALID_SCHEMA records are dead-lettered (invalid, no retry)',
     lambda: prom(f'sum(commerce_events_dead_lettered_total{{reason="invalid", topic="{INVENTORY}"}})') > before['invalid_inventory']),
    ('UNKNOWN_TYPE events are skipped and counted',
     lambda: prom('sum(commerce_events_consumed_total{consumer="order", type="unhandled"})') > before['unhandled']),
    ('DUPLICATE events are applied once, the rest counted as duplicates',
     lambda: prom('sum(commerce_events_consumed_total{type="shipment.return-received", outcome="duplicate"})') > before['duplicate']),
    ('ORPHAN events are ignored',
     lambda: prom('sum(commerce_events_consumed_total{consumer="catalog", type="inventory.stock-changed", outcome="ignored"})') > before['orphan']),
]
for name, check in checks:
    wait(name, check, 60)
    ok(name)
oversized = counts(ids['OVERSIZED'])
if 'sent' in oversized or not any(k.startswith('refused') for k in oversized):
    fail('OVERSIZED records are refused by the broker', json.dumps(oversized))
ok(f'OVERSIZED records are refused by the broker ({", ".join(k for k in oversized)})')
intruder = counts(ids['UNAUTHORISED'])
if any('allowed' in k for k in intruder) or not all(intruder.get(k, 0) > 0 for k in
                                                     ('write refused', 'read refused', 'join group order refused')):
    fail('the intruder is refused every time: write, read, join a service group', json.dumps(intruder))
ok('the intruder is refused every time: write, read, join a service group')
code, cluster = call('GET', '/api/v1/ops/kafka')
groups = {g['group']: g for g in cluster['groups']}
slow = next((g for name, g in groups.items() if name.startswith('testkit-slow-')), None)
if not slow or sum(slow['lagByTopic'].values()) == 0:
    fail('the SLOW consumer falls behind in its own group', json.dumps(slow))
service_lag = {name: sum(g['lagByTopic'].values()) for name, g in groups.items() if not name.startswith('testkit-')}
ok(f"the SLOW consumer falls behind in its own group (lag {sum(slow['lagByTopic'].values())}; "
   f"services: {json.dumps(service_lag)})")
if counts(ids['CRASHING']).get('crashed', 0) < 2:
    fail('the CRASHING consumer keeps crashing in its own group', json.dumps(counts(ids['CRASHING'])))
ok(f"the CRASHING consumer keeps crashing in its own group ({counts(ids['CRASHING']).get('crashed')} times)")
if counts(ids['FLAPPING']).get('left', 0) < 2:
    fail('the FLAPPING consumer joins and leaves', json.dumps(counts(ids['FLAPPING'])))
ok(f"the FLAPPING consumer joins and leaves ({counts(ids['FLAPPING']).get('left')} times)")
call('DELETE', '/api/v1/ops/testkit/actors')
ok('the actors stop')

print('Flood and a stuck consumer')
code, stuck = call('POST', '/api/v1/ops/testkit/actors', {'kind': 'consumer', 'type': 'STUCK', 'topic': ORDER})
code, flood = call('POST', '/api/v1/ops/testkit/actors', {'kind': 'producer', 'type': 'FLOOD', 'topic': INVENTORY,
                                                          'ratePerSecond': 400})
peak = 0
answered = failed = 0
flood_started = time.time()
while time.time() - flood_started < 30:
    code, _ = call('GET', '/api/v1/catalog/products?size=5', base=SHOP, auth=False)
    answered += code == 200
    failed += code != 200
    _, cluster = call('GET', '/api/v1/ops/kafka')
    order = next(g for g in cluster['groups'] if g['group'] == 'order')
    peak = max(peak, order['lagByTopic'].get(INVENTORY, 0))
    time.sleep(2)
call('DELETE', f"/api/v1/ops/testkit/actors/{flood['id']}")
sent = counts(flood['id']).get('sent', 0) if actor(flood['id']) else 0
if failed:
    fail('the shop keeps answering during the flood', f'{failed} of {answered + failed} requests failed')
ok(f'the shop keeps answering during the flood ({answered} of {answered} catalog requests answered)')
if peak < 100:
    fail("the flood shows as lag on the order service's inventory consumer", f'peak {peak}')
ok(f"the flood shows as lag on the order service's inventory consumer (peak {peak})")
recovered_at = wait('the lag recovers once the flood stops', lambda: (
    next(g for g in call('GET', '/api/v1/ops/kafka')[1]['groups'] if g['group'] == 'order')['lagByTopic'].get(INVENTORY, 0) < 50
    and time.time()), 300, 5)
ok(f'the lag recovers once the flood stops ({recovered_at - flood_started - 30:.0f} s after it stopped)')
stuck_group = f"testkit-stuck-{stuck['id']}"
wait('the STUCK consumer is evicted from its own group', lambda: next(
    (g['members'] == 0 for g in call('GET', '/api/v1/ops/kafka')[1]['groups'] if g['group'] == stuck_group), False), 120, 5)
ok('the STUCK consumer is evicted from its own group')
call('DELETE', '/api/v1/ops/testkit/actors')

print('What the shoppers did')
wait('shoppers have been at it for four minutes', lambda: time.time() - started > 240, 300, 10)
code, journal = call('GET', '/api/v1/ops/testkit/journal?' + urllib.parse.urlencode(
    {'from': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(started)), 'limit': 100000}))
outcomes = {}
for e in journal:
    outcomes[(e['action'], e['outcome'])] = outcomes.get((e['action'], e['outcome']), 0) + 1
settled_reasons = {}
for e in journal:
    if e['action'] == 'order.settled':
        settled_reasons[e['detail']] = settled_reasons.get(e['detail'], 0) + 1
summary = {f'{a} {o}': n for (a, o), n in sorted(outcomes.items())}
for name, key, minimum in (('orders are placed', ('order.placed', 'accepted'), 50),
                           ('confirmed orders are cancelled', ('order.cancel', 'accepted'), 3),
                           ('delivered orders are returned', ('order.return', 'accepted'), 3),
                           ('returned orders are refunded', ('order.settled', 'REFUNDED'), 1)):
    if outcomes.get(key, 0) < minimum:
        fail(f'shoppers: {name}', json.dumps(summary))
    ok(f'shoppers: {name} ({outcomes[key]})')
# Their own cancellations are counted above (order.cancel accepted); these are
# the system's, with the reason the shopper's order page showed.
for reason in ('PAYMENT_DECLINED', 'OUT_OF_STOCK'):
    if not any(reason in detail for detail in settled_reasons):
        fail(f'shoppers see orders cancelled for {reason}', json.dumps(settled_reasons))
ok(f'shoppers see orders cancelled by declined cards and for lack of stock ({json.dumps(settled_reasons)})')
call('PUT', '/api/v1/ops/testkit/shoppers', settings(20, 0))
ok('the shoppers pause')

print('Contracts')
violations = status()['contractViolations']
real = {k: v for k, v in violations.items() if k.startswith('real ')}
chaos = {k: v for k, v in violations.items() if k.startswith('chaos ')}
if real:
    fail('no real service wrote an event that breaks its contract', json.dumps(real))
if not chaos:
    fail('the contract tap saw the lab\'s bad records', json.dumps(violations))
ok(f'no real service wrote an invalid event; the tap saw {sum(chaos.values())} from the lab')

call('PUT', '/api/v1/ops/testkit/shoppers', settings(0, 0))
if test_data_before is False:
    call('PUT', '/api/v1/ops/testkit/test-data', {'on': False})
print(f'verify-testkit: {passed} checks passed')
