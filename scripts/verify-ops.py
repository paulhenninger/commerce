#!/usr/bin/env python3
"""Proves, against a running DEV stack, that the ops service and console work.

    scripts/stack.sh dev up -d --wait && scripts/verify-ops.py

1. The console is served by ops-web, and its API is for administrators only.
2. The overview shows a stopped service as down within seconds (one scrape plus
   DNS refresh), and as up again when it returns.
3. A malformed record written to an event topic is dead-lettered by its
   consumers and can be browsed and replayed from the console's API.
4. An alert (ServiceDown, from step 2) arrives as an incident, which can be
   acknowledged and annotated, and resolves itself.
5. The live feed streams events.
"""
import json
import pathlib
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
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
    request = urllib.request.Request(CONSOLE + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if token else {}),
                                              **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw and raw[:1] in b'[{' else raw.decode()
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors='replace')


def containers(service):
    return subprocess.run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=commerce-dev',
                           '--filter', f'label=com.docker.compose.service={service}'],
                          capture_output=True, text=True, check=True).stdout.split()


def docker(action, service):
    subprocess.run(['docker', action, *containers(service)], capture_output=True, check=True)


def tile(token, name):
    status, overview = call('GET', '/api/v1/ops/overview', token)
    if status != 200:
        fail('the overview answers', f'{status} {overview}')
    return next(s for s in overview['services'] if s['name'] == name)


print('Console and access')
status, page = call('GET', '/')
if status != 200 or '<div id="root">' not in page:
    fail('ops-web serves the console', f'{status}')
ok('ops-web serves the console')
status, _ = call('GET', '/api/v1/ops/overview')
if status != 401:
    fail('the console API refuses anonymous callers', str(status))
ok('the console API refuses anonymous callers')
status, _ = call('GET', '/api/v1/ops/overview', headers={'X-Auth-User-Id': '1', 'X-Auth-Role': 'ADMIN',
                                                       'X-Auth-Strong': 'true'})
if status != 401:
    fail('forged identity headers are not believed', str(status))
ok('forged identity headers are not believed')
status, login = call('POST', '/api/v1/auth/login', body={'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                         'password': ENV['DEV_ADMIN_PASSWORD']})
if status != 200 or 'accessToken' not in login:
    fail('the administrator signs in on the console', f'{status} {login} (run scripts/smoke.sh once first)')
token = login['accessToken']
ok('the administrator signs in on the console')
status, config = call('GET', '/api/v1/ops/config', token)
if status != 200 or config['environment'] != 'dev':
    fail('the console knows its environment', f'{status} {config}')
ok('the console knows its environment')

print('Live feed')
received = []


def listen():
    request = urllib.request.Request(CONSOLE + '/api/v1/ops/live',
                                     headers={'Authorization': f'Bearer {token}', 'X-Commerce-Client': 'web'})
    try:
        with urllib.request.urlopen(request, timeout=20) as stream:
            for raw in stream:
                if raw.startswith(b'data:'):
                    received.append(json.loads(raw[5:]))
                    if len(received) >= 3:
                        return
    except Exception as e:  # noqa: BLE001 — reported below
        received.append({'error': str(e)})


listener = threading.Thread(target=listen, daemon=True)
listener.start()

print('Dead letters')
kafka = containers('kafka-1')[0]
password = ENV['KAFKA_PASSWORD_TESTKIT_CHAOS']
marker = f'verify-ops-{int(time.time())}'
script = f"""
cat > /tmp/chaos.properties <<'P'
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="testkit-chaos" password="{password}";
P
printf '%s\\n' '{marker}:this is not an event' | KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --producer.config /tmp/chaos.properties --topic commerce.order.events.v1 \
  --property parse.key=true --property key.separator=:
rm /tmp/chaos.properties
"""
# Bytes, not text: on Windows, text mode would turn the newlines into CRLF.
result = subprocess.run(['docker', 'exec', '-i', kafka, 'bash', '-s'], input=script.encode(), capture_output=True)
if result.returncode != 0:
    fail('a malformed record can be written by the chaos principal', result.stderr.decode(errors='replace')[-500:])
ok('a malformed record is written to the order topic (as the DEV chaos principal)')
letters = []
for _ in range(30):
    status, letters = call('GET', '/api/v1/ops/kafka/dead-letters/commerce.order.events.v1.dlt', token)
    mine = [d for d in letters if d.get('key') == marker] if status == 200 else []
    if len({d['consumerGroup'] for d in mine}) >= 4:
        break
    time.sleep(1)
mine = [d for d in letters if d.get('key') == marker]
groups = sorted({d['consumerGroup'] for d in mine})
if len(groups) < 4:
    fail('every consumer of the topic dead-letters it', f'groups: {groups}')
ok(f'every consumer of the topic dead-letters it ({", ".join(groups)})')
if not all('InvalidEventException' in (d.get('exception') or '') for d in mine):
    fail('the dead letters say why', json.dumps(mine[0])[:500])
ok('the dead letters say why (InvalidEventException), and show the payload')
status, replayed = call('POST', '/api/v1/ops/kafka/dead-letters/commerce.order.events.v1.dlt/replay', token,
                        {'partition': mine[0]['partition'], 'offset': mine[0]['offset']})
if status != 200 or replayed.get('to') != 'commerce.order.events.v1':
    fail('a dead letter can be replayed to its topic', f'{status} {replayed}')
ok(f"a dead letter can be replayed to its topic (offset {replayed['newOffset']})")
status, actions = call('GET', '/api/v1/ops/incidents?status=RESOLVED', token)
if not any(i['alertname'] == 'DeadLetterReplayed' for i in actions['items']):
    fail('the replay is on the record')
ok('the replay is on the record')

listener.join(timeout=25)
if not received or 'error' in received[0]:
    fail('the live feed streams', str(received[:1]))
ok(f'the live feed streams ({len(received)} ticks, '
   f'{sum(len(t.get("events", [])) for t in received)} events seen)')

print('Overview and incidents')
before = tile(token, 'catalog')
if not before['up']:
    fail('catalog is up to begin with')
stopped = time.time()
docker('stop', 'catalog')
try:
    for _ in range(60):
        if not tile(token, 'catalog')['up']:
            break
        time.sleep(1)
    took = time.time() - stopped
    if tile(token, 'catalog')['up']:
        fail('the overview shows a stopped service as down', f'still up after {took:.0f}s')
    ok(f'the overview shows a stopped service as down ({took:.0f} s)')

    incident = None
    for _ in range(40):
        status, firing = call('GET', '/api/v1/ops/incidents?status=FIRING', token)
        incident = next((i for i in firing['items'] if i['alertname'] == 'ServiceDown' and i['service'] == 'catalog'),
                        None)
        if incident:
            break
        time.sleep(5)
    if not incident:
        fail('the ServiceDown alert arrives as an incident')
    ok(f'the ServiceDown alert arrives as an incident ({time.time() - stopped:.0f} s after the stop)')
    status, acked = call('POST', f"/api/v1/ops/incidents/{incident['id']}/ack", token)
    if status != 200 or acked['acknowledgedBy'] != ENV['BOOTSTRAP_ADMIN_EMAIL']:
        fail('the incident can be acknowledged', f'{status} {acked}')
    ok('the incident can be acknowledged')
    status, noted = call('POST', f"/api/v1/ops/incidents/{incident['id']}/notes", token,
                         {'note': 'Stopped on purpose by verify-ops.py'})
    if status != 200 or noted['notes'][-1]['note'] != 'Stopped on purpose by verify-ops.py':
        fail('a note can be added', f'{status}')
    ok('a note can be added')
finally:
    docker('start', 'catalog')

for _ in range(60):
    if tile(token, 'catalog')['up']:
        break
    time.sleep(2)
else:
    fail('the overview shows the service up again')
ok('the overview shows the service up again')
for _ in range(40):
    status, detail = call('GET', f"/api/v1/ops/incidents/{incident['id']}", token)
    if detail['status'] == 'RESOLVED':
        break
    time.sleep(5)
else:
    fail('the incident resolves itself when the service is back', detail['status'])
ok('the incident resolves itself when the service is back')

print(f'verify-ops: {passed} checks passed')
