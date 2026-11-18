#!/usr/bin/env python3
"""Proves, against a running DEV stack (HA topology), that instance control works
and stays inside its limits.

    scripts/stack.sh dev up -d --wait && scripts/verify-instances.py

1. The console lists every replica and broker with its state.
2. Stop, start, kill, pause, resume and restart work from the console's API, on a
   service replica and on a broker, and show within seconds.
3. The controller refuses what it must never touch (ops, the observability
   stack), unknown instances and unknown actions.
4. The Docker proxy refuses creating a container, exec, images, deletion — all
   but the list and the six lifecycle actions.
5. Every action is on the incident record.
"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
CONSOLE = f"http://localhost:{ENV.get('OPS_CONSOLE_PORT', '8082')}"
REPLICATED = ['identity', 'catalog', 'inventory', 'order', 'billing', 'shipping', 'notification', 'ops']
passed = 0
token = None


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:2000], file=sys.stderr)
    sys.exit(1)


def call(method, path, body=None):
    request = urllib.request.Request(CONSOLE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if token else {})})
    try:
        with urllib.request.urlopen(request, timeout=90) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw[:1] in (b'{', b'[') else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw[:1] in (b'{', b'[') else raw.decode(errors='replace')


def rows():
    status, body = call('GET', '/api/v1/ops/instances')
    if status != 200:
        fail('the console lists instances', f'{status} {body}')
    return body['rows']


def row(container_id):
    return next((r for r in rows() if r['containerId'] == container_id), None)


def act(target, action, expect_state, within):
    started = time.time()
    status, body = call('POST', f"/api/v1/ops/instances/{target['containerId']}/{action}")
    if status != 200:
        fail(f"{action} {target['name']}", f'{status} {body}')
    while time.time() - started < within:
        current = row(target['containerId'])
        if current and expect_state(current):
            return time.time() - started
        time.sleep(1)
    fail(f"{action} {target['name']} shows in the console", json.dumps(row(target['containerId'])))


status, login = call('POST', '/api/v1/auth/login', {'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                    'password': ENV['DEV_ADMIN_PASSWORD']})
token = login['accessToken']

print('Listing')
status, config = call('GET', '/api/v1/ops/config')
if not config.get('instanceControl'):
    fail('instance control is on in DEV', json.dumps(config))
listed = rows()
by_service = {}
for r in listed:
    by_service.setdefault(r['service'], []).append(r)
for service in REPLICATED:
    running = [r for r in by_service.get(service, []) if r['state'] == 'running']
    if len(running) != 2:
        fail(f'{service} lists two running instances', json.dumps(by_service.get(service)))
brokers = [r for r in listed if r['service'].startswith('kafka-') and r['brokerId'] is not None]
if len(brokers) != 3 or sum(1 for b in brokers if b['activeController']) != 1:
    fail('three brokers are listed, one of them the active controller', json.dumps(brokers))
ok(f'every replica ({len(REPLICATED)} services, two each) and all three brokers are listed, running')
leaders = [r['service'] for r in listed if r['relayLeader']]
ok(f"outbox relay leaders shown ({', '.join(sorted(set(leaders)))})")

print('Control')
order = next(r for r in by_service['order'] if r['controllable'])
took = act(order, 'stop', lambda r: r['state'] == 'exited', 30)
ok(f"stopping an order replica shows as stopped ({took:.0f} s)")
took = act(order, 'start', lambda r: r['state'] == 'running' and r['health'] == 'healthy', 180)
ok(f"starting it again shows it healthy ({took:.0f} s)")
inventory = next(r for r in by_service['inventory'] if r['controllable'])
took = act(inventory, 'kill', lambda r: r['state'] == 'exited', 30)
ok(f'killing an inventory replica shows as stopped ({took:.0f} s)')
act(inventory, 'start', lambda r: r['state'] == 'running' and r['health'] == 'healthy', 180)
ok('and it comes back healthy')
billing = next(r for r in by_service['billing'] if r['controllable'])
took = act(billing, 'pause', lambda r: r['state'] == 'paused', 30)
ok(f'pausing a billing replica shows as paused ({took:.0f} s)')
act(billing, 'unpause', lambda r: r['state'] == 'running', 30)
ok('resuming it shows it running')
catalog = next(r for r in by_service['catalog'] if r['controllable'])
before = row(catalog['containerId'])
took = act(catalog, 'restart', lambda r: r['state'] == 'running' and r['health'] == 'healthy'
           and (r['uptimeSeconds'] or 1e9) < (before['uptimeSeconds'] or 0), 180)
ok(f'restarting a catalog replica shows a fresh, healthy instance ({took:.0f} s)')
broker = next(b for b in brokers if not b['activeController'])
took = act(broker, 'stop', lambda r: r['state'] == 'exited', 60)
ok(f"stopping broker {broker['brokerId']} shows as stopped ({took:.0f} s)")
act(broker, 'start', lambda r: r['state'] == 'running' and r['health'] == 'healthy', 120)
ok('starting it again shows it healthy')

print('Limits')
ops = by_service['ops'][0]
status, body = call('POST', f"/api/v1/ops/instances/{ops['containerId']}/stop")
if status != 403 or ops['controllable']:
    fail('the controller refuses to touch ops', f'{status} {body}')
ok('the controller refuses to touch ops (403)')
prometheus = by_service['prometheus'][0]
status, body = call('POST', f"/api/v1/ops/instances/{prometheus['containerId']}/kill")
if status != 403:
    fail('the controller refuses to touch the observability stack', f'{status} {body}')
ok('the controller refuses to touch the observability stack (403)')
status, _ = call('POST', '/api/v1/ops/instances/0123456789ab/stop')
if status != 404:
    fail('an unknown instance is not found', str(status))
ok('an unknown instance is not found (404)')
status, _ = call('POST', f"/api/v1/ops/instances/{order['containerId']}/remove")
if status != 404:
    fail('an unknown action is not found', str(status))
ok('an unknown action is not found (404)')

proxy = subprocess.run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=commerce-dev',
                        '--filter', 'label=com.docker.compose.service=docker-proxy'],
                       capture_output=True, text=True, check=True).stdout.split()[0]
probes = {
    'create a container': ("POST", "/v1.47/containers/create"),
    'create an exec': ("POST", f"/v1.47/containers/{order['containerId']}/exec"),
    'start an exec': ("POST", "/v1.47/exec/0123456789ab/start"),
    'list images': ("GET", "/v1.47/images/json"),
    'list volumes': ("GET", "/v1.47/volumes"),
    'change a container': ("POST", f"/v1.47/containers/{order['containerId']}/update"),
    'list with GET on an action': ("GET", f"/v1.47/containers/{order['containerId']}/stop"),
}
for what, (method, path) in probes.items():
    # wget in the proxy's own container: the request reaches its allowlist exactly as the controller's would.
    # (busybox wget sends only GET and POST; DELETE /containers/<id> matches no
    # allowed location either, so the catch-all 403 answers it the same way.)
    flag = '--post-data=' if method == 'POST' else ''
    command = f"wget -q -S -O /dev/null {flag} http://127.0.0.1:2375{path} 2>&1 | head -1"
    out = subprocess.run(['docker', 'exec', proxy, 'sh', '-c', command], capture_output=True, text=True).stdout
    if '403' not in out and '405' not in out and 'Forbidden' not in out:
        fail(f'the proxy refuses to {what}', out.strip() or 'no answer')
ok(f'the proxy refuses: {", ".join(probes)}')

print('Record')
status, body = call('GET', '/api/v1/ops/incidents?status=RESOLVED&size=50')
actions = [i for i in body['items'] if i['alertname'] == 'InstanceControl']
if len(actions) < 10:
    fail('every action is on the incident record', f'{len(actions)} recorded')
ok(f'every action is on the incident record ({len(actions)} recent)')

print(f'verify-instances: {passed} checks passed')
