#!/usr/bin/env python3
"""Proves, against a running DEV stack, that the system can be observed.

    scripts/stack.sh dev up -d --wait && scripts/smoke.sh && scripts/verify-observability.py

1. Prometheus scrapes every service.
2. One trace runs from the customer's POST /api/v1/orders, through inventory,
   billing and the order service's saga, to the sending of the confirmation email.
3. That trace's log lines are in Loki, and Grafana turns their traceId into a
   link to the trace.
4. Stopping a service fires ServiceDown within two minutes (the service is then
   started again).

Run smoke.sh first: it places the orders whose traces are looked for here.
Pass --skip-outage to leave out step 4.
"""
import base64
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
PROMETHEUS = f"http://localhost:{ENV.get('PROMETHEUS_PORT', '9090')}"
GRAFANA = f"http://localhost:{ENV.get('GRAFANA_PORT', '3000')}"
AUTH = 'Basic ' + base64.b64encode(f"admin:{ENV['GRAFANA_ADMIN_PASSWORD']}".encode()).decode()
SERVICES = ['identity', 'catalog', 'inventory', 'order', 'billing', 'shipping', 'notification']
passed = 0


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:2000], file=sys.stderr)
    sys.exit(1)


def get(url, grafana=False):
    request = urllib.request.Request(url, headers={'Authorization': AUTH} if grafana else {})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def prom(query):
    return get(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode({'query': query}))['data']['result']


def containers(service):
    """Every container (replica) of a DEV service. The Docker CLI directly: on Windows, 'bash' from
    Python can resolve to WSL's, which cannot run scripts/stack.sh against Docker Desktop."""
    out = subprocess.run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=commerce-dev',
                          '--filter', f'label=com.docker.compose.service={service}'],
                         capture_output=True, text=True, check=True).stdout.split()
    if not out:
        fail(f'{service} is running', 'no container found')
    return out


def docker(action, service):
    subprocess.run(['docker', action, *containers(service)], capture_output=True, text=True, check=True)


def services_in(trace):
    """service name → span names, from Tempo's OTLP JSON."""
    found = {}
    for batch in trace.get('batches', trace.get('resourceSpans', [])):
        name = next((a['value'].get('stringValue') for a in batch['resource']['attributes']
                     if a['key'] == 'service.name'), '?')
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            found.setdefault(name, set()).update(s['name'] for s in scope['spans'])
    return found


print('Metrics')
up = {r['metric']['service'] for r in prom('up{job="services"} == 1')}
missing = [s for s in SERVICES if s not in up]
if missing:
    fail('Prometheus scrapes every service', f'not up: {missing}')
ok('Prometheus scrapes every service')
if not prom('commerce_orders_total'):
    fail('business metrics are recorded', 'no commerce_orders_total series')
ok('business metrics are recorded')

print('Traces')
end = int(time.time())
search = get(f'{GRAFANA}/api/datasources/proxy/uid/tempo/api/search?' + urllib.parse.urlencode(
    {'q': '{ resource.service.name = "notification" && name = "send email order-confirmed" }',
     'limit': 20, 'start': end - 3600, 'end': end}), grafana=True)
whole = None
for candidate in search.get('traces', []):
    trace = get(f"{GRAFANA}/api/datasources/proxy/uid/tempo/api/traces/{candidate['traceID']}", grafana=True)
    spans = services_in(trace)
    everything = set().union(*spans.values()) if spans else set()
    if ({'order', 'inventory', 'billing', 'notification'} <= spans.keys()
            and any('/api/v1/orders' in n for n in spans.get('order', ()))
            and any(n == 'send email order-confirmed' for n in everything)):
        whole = (candidate['traceID'], spans)
        break
if not whole:
    fail('one trace runs from POST /orders to the confirmation email',
         f"searched {len(search.get('traces', []))} recent traces with a confirmation email")
trace_id, spans = whole
ok('one trace runs from POST /orders to the confirmation email '
   f"({trace_id}: {', '.join(sorted(spans))}; {sum(len(v) for v in spans.values())} span names)")
if 'simulators' in spans:
    ok('the call to the payment provider is in the same trace')
else:
    fail('the call to the payment provider is in the same trace', f'services: {sorted(spans)}')

print('Logs')
lines = []
for _ in range(20):
    result = get(f'{GRAFANA}/api/datasources/proxy/uid/loki/loki/api/v1/query_range?' + urllib.parse.urlencode(
        {'query': f'{{environment="dev"}} |= "{trace_id}"', 'start': (end - 3600) * 10**9,
         'end': (int(time.time()) + 60) * 10**9, 'limit': 100}), grafana=True)
    lines = [(s['stream'].get('service'), v[1]) for s in result['data']['result'] for v in s['values']]
    if {svc for svc, _ in lines} >= {'order', 'inventory', 'billing'}:
        break
    time.sleep(3)
by_service = sorted({svc for svc, _ in lines})
if not {'order', 'inventory', 'billing'} <= set(by_service):
    fail('the trace\'s log lines from every service are in Loki', f'found lines from {by_service}')
ok(f'the trace\'s log lines from every service are in Loki ({len(lines)} lines: {", ".join(by_service)})')
if not all(f'"traceId":"{trace_id}"' in line for _, line in lines if line.startswith('{')):
    fail('each of those lines carries the trace id as a field')
ok('each of those lines carries the trace id as a field')
loki = get(f'{GRAFANA}/api/datasources/uid/loki', grafana=True)
fields = loki.get('jsonData', {}).get('derivedFields', [])
if not any(f.get('datasourceUid') == 'tempo' and 'traceId' in f.get('matcherRegex', '') for f in fields):
    fail('Grafana links a log line\'s traceId to the trace', json.dumps(fields))
ok('Grafana links a log line\'s traceId to the trace')

if '--skip-outage' not in sys.argv:
    print('Alerts')
    victim = 'catalog'
    stopped = time.time()
    docker('stop', victim)
    try:
        firing = False
        while time.time() - stopped < 150:
            alerts = get(f'{PROMETHEUS}/api/v1/alerts')['data']['alerts']
            if any(a['labels'].get('alertname') == 'ServiceDown' and a['labels'].get('service') == victim
                   and a['state'] == 'firing' for a in alerts):
                firing = True
                break
            time.sleep(5)
        took = int(time.time() - stopped)
        if not firing or took > 120:
            fail('stopping a service fires ServiceDown within 2 minutes', f'firing={firing} after {took}s')
        ok(f'stopping a service fires ServiceDown within 2 minutes ({took} s)')
    finally:
        docker('start', victim)
    for _ in range(60):
        if not any(a['labels'].get('alertname') == 'ServiceDown' and a['labels'].get('service') == victim
                   for a in get(f'{PROMETHEUS}/api/v1/alerts')['data']['alerts']):
            break
        time.sleep(5)
    else:
        fail('the alert clears when the service is back')
    ok('the alert clears when the service is back')

print(f'verify-observability: {passed} checks passed')
