#!/usr/bin/env python3
"""Proves ADR-033 against a FRESH DEV stack: every data simulation is off until
someone switches it on, switching off stops it and keeps what it made, and the
switches survive a testkit restart.

    scripts/stack.sh dev down -v && scripts/stack.sh dev up -d --build --wait
    scripts/verify-switches.py              # --quiet-minutes N (default 10)

1. Fresh: one account (the administrator), no products, nothing switched on,
   and no event written anywhere for --quiet-minutes.
2. Test data on: the shopper account, the products and their stock appear.
   Off: the data stays, and stock is no longer topped up.
3. Shoppers, a chaos actor and a simulator fault each start when switched on
   and stop when switched off.
4. A testkit restart keeps what is on (and what is off).

Everything goes through the ops console's API, as an operator would. Leaves
everything off, as it found it.
"""
import argparse
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
T = '/api/v1/ops/testkit'
OFF = {'count': 0, 'ordersPerMinute': 0, 'cancelRate': 0.1, 'returnRate': 0.1, 'declineRate': 0.05,
       'outOfStockRate': 0.02}
NO_FAULT = {'outage': False, 'latencyMs': 0, 'errorRate': 0.0}
passed = 0
token = None


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:3000], file=sys.stderr)
    try:
        everything_off()
    except Exception:  # noqa: BLE001 — best effort clean-up
        pass
    sys.exit(1)


def call(method, path, body=None, base=CONSOLE, auth=True, again=True):
    code, answer = _call(method, path, body, base, auth)
    # An access token is short-lived; after a long pause (or the host sleeping)
    # sign in again once, as an operator would.
    if code == 401 and auth and again and path != '/api/v1/auth/login':
        sign_in()
        code, answer = _call(method, path, body, base, auth)
    return code, answer


def _call(method, path, body, base, auth):
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
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return 0, str(e)


def prom(query):
    with urllib.request.urlopen(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode({'query': query}),
                                timeout=20) as r:
        result = json.loads(r.read())['data']['result']
    return float(result[0]['value'][1]) if result else 0.0


def status():
    code, body = call('GET', f'{T}/status')
    return body if code == 200 else None


def wait(name, check, timeout, every=3):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            value = check()
        except Exception:  # noqa: BLE001 — still coming back
            value = None
        if value:
            return value
        time.sleep(every)
    # Once more after the deadline: when the host sleeps (Windows Modern
    # Standby) the clock jumps past it while nothing could run.
    try:
        value = check()
    except Exception:  # noqa: BLE001
        value = None
    if value:
        return value
    fail(name, f'not true after {timeout} s')


def sign_in():
    global token
    code, login = call('POST', '/api/v1/auth/login', {'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                      'password': ENV['DEV_ADMIN_PASSWORD']})
    if code != 200:
        fail('the administrator signs in', f'{code} {login}')
    token = login['accessToken']


def everything_off():
    call('PUT', f'{T}/switches', {'testData': False, 'shoppers': OFF, 'actors': [],
                                   'faults': {'payment': NO_FAULT, 'carrier': NO_FAULT}})


def products():
    code, body = call('GET', '/api/v1/catalog/products?size=50', base=SHOP, auth=False)
    return body['total'] if code == 200 else -1


def bash():
    """Git Bash on Windows (plain 'bash' there can be WSL's, which cannot reach Docker Desktop)."""
    if sys.platform == 'win32':
        for candidate in (r'C:\Program Files\Git\bin\bash.exe', r'C:\Program Files (x86)\Git\bin\bash.exe'):
            if os.path.exists(candidate):
                return candidate
    return 'bash'


def restart_testkit():
    subprocess.run([bash(), str(ROOT / 'scripts/stack.sh'), 'dev', 'restart', 'testkit'], check=True,
                   capture_output=True)
    wait('the testkit answers again after a restart', status, 240)


parser = argparse.ArgumentParser()
parser.add_argument('--quiet-minutes', type=int, default=10)
parser.add_argument('--skip-fresh', action='store_true',
                    help='skip section 1 (needs a fresh stack); start at test data')
args = parser.parse_args()

if args.skip_fresh:
    wait('the administrator signs in', lambda: (sign_in(), True)[1], 60)
else:
    print('A fresh stack has nothing switched on')
    wait('the testkit has made the administrator', lambda: (sign_in(), (status() or {}).get('adminReady'))[1], 300)
    ok('the administrator was made at startup and signs in')
    code, users = call('GET', '/api/v1/admin/users?size=50', base=SHOP)
    # Identity is Bastion: its pages are Spring's shape (totalElements), not this system's.
    if code != 200 or users.get('totalElements') != 1:
        fail('it is the only account', f'{code} {users}')
    ok('it is the only account')
    code, _ = call('POST', '/api/v1/auth/login', {'email': 'shopper@example.com',
                                                  'password': ENV['DEV_SHOPPER_PASSWORD']}, auth=False)
    if code == 200:
        fail('shopper@example.com does not exist yet')
    ok('shopper@example.com does not exist yet')
    if products() != 0:
        fail('the catalog is empty', f'{products()} products')
    ok('the catalog is empty')
    s = status()
    switches = s['switches']
    if s['testData'] or switches['shoppers']['count'] != 0 or switches['actors'] \
            or any(f.get('outage') or f.get('latencyMs') or f.get('errorRate') for f in switches['faults'].values()):
        fail('test data, shoppers, actors and faults are all off', json.dumps(switches))
    ok('test data, shoppers, actors and faults are all off')
    print(f'  ...   watching for {args.quiet_minutes} min', flush=True)
    time.sleep(args.quiet_minutes * 60)
    window = f'{args.quiet_minutes}m'
    published = prom(f'sum(increase(commerce_outbox_published_total[{window}]))')
    written = prom(f'sum(max_over_time(commerce_kafka_topic_end_offset[{window}]) '
                   f'- min_over_time(commerce_kafka_topic_end_offset[{window}]))')
    if published > 0.5 or written > 0.5:
        fail(f'no event was written in {window}', f'{published:.0f} published by services, {written:.0f} records on topics')
    ok(f'no event was written anywhere in {args.quiet_minutes} min')

print('Test data')
call('PUT', f'{T}/test-data', {'on': True})
wait('test data is seeded once switched on', lambda: status()['seeded'], 300)
if products() < 24:
    fail('the products appear', f'{products()}')
code, _ = call('POST', '/api/v1/auth/login', {'email': 'shopper@example.com',
                                              'password': ENV['DEV_SHOPPER_PASSWORD']}, auth=False)
if code != 200:
    fail('shopper@example.com can sign in', str(code))
ok(f'switched on: shopper@example.com and {products()} products appear')
call('PUT', f'{T}/test-data', {'on': False})
# A top-up is a stock change through inventory's outbox; with test data off and
# nothing else on, none should come in the next two top-up periods.
before = prom('sum(commerce_outbox_published_total{service="inventory"})')
time.sleep(75)
after = prom('sum(commerce_outbox_published_total{service="inventory"})')
if products() < 24 or after - before > 0.5:
    fail('switched off: the data stays and stock is no longer topped up',
         f'{products()} products, {after - before:.0f} inventory events')
ok('switched off: the data stays, and stock is no longer topped up')

print('Shoppers, actors and faults')
call('PUT', f'{T}/test-data', {'on': True})
call('PUT', f'{T}/shoppers', {**OFF, 'count': 3, 'ordersPerMinute': 6})
wait('shoppers sign in once switched on', lambda: status()['shoppers']['ready'] >= 3, 180)
ok('shoppers switched on sign in')
code, actor = call('POST', f'{T}/actors', {'kind': 'producer', 'type': 'ORPHAN',
                                           'topic': 'commerce.order.events.v1', 'ratePerSecond': 1})
if code != 201:
    fail('a chaos actor starts', f'{code} {actor}')
code, _ = call('PUT', f'{T}/faults/carrier', {'outage': False, 'latencyMs': 300, 'errorRate': 0.0})
if code != 200:
    fail('a simulator fault is set', str(code))
ok('a chaos actor and a simulator fault switched on')

print('A testkit restart keeps the switches')
restart_testkit()
sign_in()
wait('the switches are put back after a restart', lambda: (
    status()['testData'] and status()['shoppers']['settings']['count'] == 3
    and any(a['running'] and a['type'] == 'ORPHAN' for a in status()['actors'])), 180)
code, faults = call('GET', f'{T}/faults')
if code != 200 or faults['carrier']['latencyMs'] != 300:
    fail('the simulator fault is set again', f'{code} {faults}')
ok('after a restart: test data, shoppers, the actor and the fault are on again')
everything_off()
s = status()
if s['testData'] or s['shoppers']['settings']['count'] != 0 or any(a['running'] for a in s['actors']):
    fail('switching off stops each of them', json.dumps(s['switches']))
wait('the shoppers leave', lambda: status()['shoppers']['ready'] == 0, 60)
ok('switched off: shoppers, the actor and the fault stop')
restart_testkit()
sign_in()
time.sleep(20)
s = status()
if s['testData'] or s['shoppers']['settings']['count'] != 0 or s['actors']:
    fail('after a restart, what was off stays off', json.dumps(s['switches']))
ok('after a restart, what was off stays off')

print(f'verify-switches: {passed} checks passed')
