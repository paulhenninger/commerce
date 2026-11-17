#!/usr/bin/env python3
"""Checks that a running DEV stack is the HA topology, and that it works.

    scripts/stack.sh dev up -d --wait && scripts/verify-ha.py

1. Every replicated service has two healthy instances; Kafka has three brokers.
2. Every topic has three copies and needs two in sync; none is under-replicated.
3. Prometheus scrapes both instances of each service; the ops console sees
   three brokers.
4. The whole flow works on it (scripts/smoke.sh).
"""
import json
import os
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
PROMETHEUS = f"http://localhost:{ENV.get('PROMETHEUS_PORT', '9090')}"
REPLICATED = ['identity', 'catalog', 'inventory', 'order', 'billing', 'shipping', 'notification', 'ops']
passed = 0


def ok(name):
    global passed
    passed += 1
    print(f'  ok   {name}', flush=True)


def fail(name, detail=''):
    print(f'  FAIL {name}\n       {detail}'[:2000], file=sys.stderr)
    sys.exit(1)


def containers(service):
    out = subprocess.run(['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.project=commerce-dev',
                          '--filter', f'label=com.docker.compose.service={service}',
                          '--format', '{{.Names}} {{.Status}}'], capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line.strip()]


def bash():
    if sys.platform == 'win32':
        for candidate in (r'C:\Program Files\Git\bin\bash.exe', r'C:\Program Files (x86)\Git\bin\bash.exe'):
            if os.path.exists(candidate):
                return candidate
    return 'bash'


print('Instances')
for service in REPLICATED:
    found = containers(service)
    healthy = [c for c in found if '(healthy)' in c]
    if len(healthy) != 2:
        fail(f'{service} has two healthy instances', '\n'.join(found))
ok(f'every replicated service has two healthy instances ({", ".join(REPLICATED)})')
brokers = [c for b in ('kafka-1', 'kafka-2', 'kafka-3') for c in containers(b) if '(healthy)' in c]
if len(brokers) != 3:
    fail('Kafka has three healthy brokers', str(brokers))
ok('Kafka has three healthy brokers')

print('Replication')
kafka = containers('kafka-1')[0].split()[0]
script = f"""
cat > /tmp/ops.properties <<'P'
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="ops" password="{ENV['KAFKA_PASSWORD_OPS']}";
P
KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --command-config /tmp/ops.properties --describe --topic 'commerce\\..*' | grep -E '^Topic:'
rm /tmp/ops.properties
"""
result = subprocess.run(['docker', 'exec', '-i', kafka, 'bash', '-s'], input=script.encode(), capture_output=True)
lines = result.stdout.decode().splitlines()
if len(lines) < 12:
    fail('every topic can be described', result.stderr.decode()[-500:])
bad = [l for l in lines if 'ReplicationFactor: 3' not in l or 'min.insync.replicas=2' not in l]
if bad:
    fail('every topic has three copies and needs two in sync', '\n'.join(bad))
ok(f'all {len(lines)} topics have three copies and need two in sync')

print('Monitoring')
with urllib.request.urlopen(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode(
        {'query': 'count by (service) (up{job="services"} == 1)'}), timeout=20) as r:
    counts = {s['metric']['service']: float(s['value'][1]) for s in json.loads(r.read())['data']['result']}
short = {s: counts.get(s, 0) for s in REPLICATED if counts.get(s, 0) != 2}
if short:
    fail('Prometheus scrapes both instances of every service', json.dumps(counts))
ok('Prometheus scrapes both instances of every service')
with urllib.request.urlopen(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode(
        {'query': 'max(commerce_kafka_brokers)'}), timeout=20) as r:
    result = json.loads(r.read())['data']['result']
if not result or float(result[0]['value'][1]) != 3:
    fail('the ops service sees three brokers', json.dumps(result))
ok('the ops service sees three brokers')

print('The flow')
smoke = subprocess.run([bash(), str(ROOT / 'scripts/smoke.sh')], capture_output=True, text=True)
tail = (smoke.stdout + smoke.stderr).strip().splitlines()[-3:]
if smoke.returncode != 0:
    fail('the smoke test passes on the HA topology', '\n'.join(tail))
ok(f'the smoke test passes on the HA topology ({tail[-1].strip()})')

print(f'verify-ha: {passed} checks passed')
