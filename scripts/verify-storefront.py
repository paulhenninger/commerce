#!/usr/bin/env python3
"""Checks what the storefront's pages rely on, against a running DEV stack.

    scripts/stack.sh dev up -d --wait && scripts/verify-storefront.py

The customer journey itself — sign up, shop, check out, watch the order, cancel,
return — and the back office are exercised request by request by
scripts/smoke.sh, exactly as the pages make those requests. This adds:

1. Every page's address serves the app (it routes in the browser), under the
   storefront's content security policy, with its script and styles.
2. An error carries the request id the page shows the customer.
3. Checkout's price-changed answer has the shape the page reads.
4. A product an administrator creates and stocks appears in the shop and can be
   bought — the back office works without any seeded data.

The checkout checks use the seeded shopper and products: DEV's test data is off
until switched on (ADR-033), so this switches it on through the ops console and
puts it back as it found it.
"""
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
SHOP = f"http://localhost:{ENV.get('STOREFRONT_PORT', '8081')}"
CONSOLE = f"http://localhost:{ENV.get('OPS_CONSOLE_PORT', '8082')}"
passed = 0


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:2000], file=sys.stderr)
    sys.exit(1)


def call(method, path, token=None, body=None, headers=None):
    request = urllib.request.Request(SHOP + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if token else {}),
                                              **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=30) as r:
            raw = r.read()
            return r.status, dict(r.headers), (json.loads(raw) if raw[:1] in (b'{', b'[') else raw.decode(errors='replace'))
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, dict(e.headers), (json.loads(raw) if raw[:1] in (b'{', b'[') else raw.decode(errors='replace'))


print('Pages')
for path in ['/', '/products/MUG-BLUE', '/cart', '/checkout', '/orders', '/orders/01JD3K8Q2M7XW4T9V6B5N1C0ZA',
             '/backoffice/products', '/backoffice/stock', '/backoffice/orders', '/login', '/account']:
    status, headers, page = call('GET', path)
    if status != 200 or '<div id="root">' not in page:
        fail(f'{path} serves the app', f'{status}')
    if "script-src 'self'" not in headers.get('Content-Security-Policy', ''):
        fail(f'{path} is served under the content security policy', str(headers.get('Content-Security-Policy')))
ok('every page address serves the app, under the content security policy')
status, _, page = call('GET', '/')
assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', page)
for asset in assets:
    status, headers, _ = call('GET', asset)
    if status != 200 or 'immutable' not in headers.get('Cache-Control', ''):
        fail(f'{asset} is served and cached for good', f'{status} {headers.get("Cache-Control")}')
ok(f'its script and styles are served and cached for good ({len(assets)} files)')

def console(method, path, token=None, body=None):
    """The ops console's API (the testkit's switches are behind it)."""
    request = urllib.request.Request(CONSOLE + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if token else {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw[:1] in (b'[', b'{') else None


admin = console('POST', '/api/v1/auth/login', body={'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                    'password': ENV['DEV_ADMIN_PASSWORD']})['accessToken']
test_data_before = console('GET', '/api/v1/ops/testkit/status', admin)['testData']
console('PUT', '/api/v1/ops/testkit/test-data', admin, {'on': True})
deadline = time.time() + 300
while not console('GET', '/api/v1/ops/testkit/status', admin)['seeded']:
    if time.time() > deadline:
        fail('test data is seeded once switched on', 'not seeded after 300 s')
    time.sleep(3)
ok('test data switched on and seeded')

print('Errors and checkout')
status, headers, body = call('GET', '/api/v1/orders/01JD3K8Q2M7XW4T9V6B5N1C0ZA')
if status != 401 or not headers.get('X-Request-Id'):
    fail('an error carries the request id the page shows', f'{status} {headers}')
ok('an error carries the request id the page shows')
status, _, login = call('POST', '/api/v1/auth/login', body={'email': 'shopper@example.com',
                                                             'password': ENV['DEV_SHOPPER_PASSWORD']})
if status != 200:
    fail('the seeded shopper signs in', f'{status} {login}')
shopper = login['accessToken']
status, _, product = call('GET', '/api/v1/catalog/products/MUG-BLUE')
order = {'lines': [{'sku': 'MUG-BLUE', 'quantity': 2}], 'paymentMethod': 'tok_visa', 'expectedTotalCents': 1,
         'shippingAddress': {'name': 'Ada', 'line1': '1 Street', 'city': 'Town', 'postalCode': '12345', 'country': 'US'}}
status, _, body = call('POST', '/api/v1/orders', shopper, order, {'Idempotency-Key': f'verify-{time.time_ns()}'})
if status != 409 or body.get('code') != 'PRICE_CHANGED' or body.get('totalCents') != 2 * product['priceCents'] \
        or body['lines'][0]['unitPriceCents'] != product['priceCents']:
    fail("checkout's price-changed answer has the current total and prices", f'{status} {body}')
ok("checkout's price-changed answer has the current total and prices")

print('Back office from nothing')
status, _, login = call('POST', '/api/v1/auth/login', body={'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                             'password': ENV['DEV_ADMIN_PASSWORD']})
admin = login['accessToken']
sku = f'NEW-{int(time.time())}'
status, _, saved = call('PUT', f'/api/v1/backoffice/catalog/products/{sku}', admin,
                        {'name': 'Brand-new lamp', 'description': 'Added by verify-storefront.py', 'category': 'home',
                         'priceCents': 2500, 'currency': 'USD', 'imageKey': None, 'active': True})
if status != 200:
    fail('an administrator adds a product nobody has seen', f'{status} {saved}')
ok('an administrator adds a product nobody has seen')
for _ in range(30):
    status, _, level = call('PUT', f'/api/v1/backoffice/inventory/stock/{sku}', admin, {'onHand': 7})
    if status == 200:
        break
    time.sleep(1)
if status != 200 or level['available'] != 7:
    fail('and gives it stock', f'{status} {level}')
ok('and gives it stock')
for _ in range(30):
    status, _, shown = call('GET', f'/api/v1/catalog/products/{sku}')
    if status == 200 and shown['available'] == 7:
        break
    time.sleep(1)
else:
    fail('the shop shows it in stock', f'{status} {shown}')
ok('the shop shows it in stock')
order['lines'] = [{'sku': sku, 'quantity': 1}]
order['expectedTotalCents'] = 2500
for _ in range(30):
    status, _, placed = call('POST', '/api/v1/orders', shopper, order, {'Idempotency-Key': f'verify-{time.time_ns()}'})
    if status == 202:
        break
    time.sleep(1)
if status != 202:
    fail('a customer can buy it', f'{status} {placed}')
for _ in range(60):
    status, _, detail = call('GET', f"/api/v1/orders/{placed['id']}", shopper)
    if detail['status'] == 'CONFIRMED':
        break
    time.sleep(1)
else:
    fail('a customer can buy it', detail['status'])
ok('a customer can buy it (order confirmed)')
call('POST', f"/api/v1/orders/{placed['id']}/cancel", shopper)
call('PUT', f'/api/v1/backoffice/catalog/products/{sku}', admin,
     {'name': 'Brand-new lamp', 'description': 'Added by verify-storefront.py', 'category': 'home',
      'priceCents': 2500, 'currency': 'USD', 'imageKey': None, 'active': False})
ok('the test order is cancelled and the product taken off sale again')

if not test_data_before:
    console('PUT', '/api/v1/ops/testkit/test-data', admin, {'on': False})
print(f'verify-storefront: {passed} checks passed')
