#!/usr/bin/env python3
"""The load baseline (milestone 11): drives the simulated shoppers at increasing
rates against a running DEV stack and measures, at each step, what the latency
targets in architecture §8 are about and where the system runs out first.

    scripts/load-baseline.py                       # 1 → 20 orders/s, ~30 min
    scripts/load-baseline.py --steps 60,600 --measure 120

Each step sets the shoppers' rate, lets it settle (--ramp s), then measures over
--measure s, all from Prometheus:

  customer side   catalog read and POST /orders p95 as the shoppers see them,
                  through the edge (commerce_testkit_request_seconds)
  saga            place → confirmed p95 (commerce_order_saga_seconds)
  auth hop        identity's /internal/authz p95 (design review C)
  saturation      5xx share, consumer lag per group, DB pool waits, CPU per
                  service, and whether the load generator itself kept up
                  (skipped_busy: then the step measured the testkit, not the shop)

Writes build/load/baseline-<time>.md and prints the table. Switches test data
and shoppers on for the run and puts the switches back as it found them.
"""
import argparse
import datetime
import json
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
CONSOLE = f"http://localhost:{ENV.get('OPS_CONSOLE_PORT', '8082')}"
PROMETHEUS = f"http://localhost:{ENV.get('PROMETHEUS_PORT', '9090')}"
T = '/api/v1/ops/testkit'
SERVICES = ['identity', 'catalog', 'inventory', 'order', 'billing', 'shipping', 'notification']
token = None


def sign_in():
    global token
    token = None
    token = call('POST', '/api/v1/auth/login', {'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                'password': ENV['DEV_ADMIN_PASSWORD']})['accessToken']


def call(method, path, body=None, again=True):
    request = urllib.request.Request(CONSOLE + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                                              **({'Authorization': f'Bearer {token}'} if token else {})})
    try:
        with urllib.request.urlopen(request, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw[:1] in (b'[', b'{') else None
    except urllib.error.HTTPError as e:
        if e.code == 401 and again and path != '/api/v1/auth/login':
            sign_in()
            return call(method, path, body, again=False)
        raise SystemExit(f'{method} {path}: {e.code} {e.read()[:300]!r}')


def prom(query):
    with urllib.request.urlopen(f'{PROMETHEUS}/api/v1/query?' + urllib.parse.urlencode({'query': query}),
                                timeout=30) as r:
        result = json.loads(r.read())['data']['result']
    return result


def scalar(query):
    result = prom(query)
    try:
        value = float(result[0]['value'][1]) if result else float('nan')
    except (ValueError, IndexError):
        value = float('nan')
    return value


def count(query):
    """A rate that is simply absent when nothing happened (no failures, no skips): 0, not unknown."""
    value = scalar(query)
    return 0.0 if value != value else value


def by(query, label):
    return {r['metric'].get(label, '?'): float(r['value'][1]) for r in prom(query)}


def p95(selector, w, metric='commerce_testkit_request_seconds'):
    return scalar(f'histogram_quantile(0.95, sum by (le) (rate({metric}_bucket{{{selector}}}[{w}])))')


def container_cpu():
    """CPU per container (cores), one sample, from Docker: covers MySQL, Kafka and the edges too."""
    try:
        out = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}}'],
                             capture_output=True, text=True, timeout=60).stdout
    except (OSError, subprocess.TimeoutExpired):
        return {}
    cpu = {}
    for line in out.splitlines():
        name, _, pct = line.partition(' ')
        if name.startswith('commerce-dev-'):
            short = name[len('commerce-dev-'):].rsplit('-', 1)[0] if name[-2] == '-' else name[len('commerce-dev-'):]
            cpu[short] = cpu.get(short, 0) + float(pct.rstrip('%') or 0) / 100
    return cpu


def shoppers(rate_per_minute):
    # Enough shoppers that none comes near a per-address limit (one order every
    # few seconds each), within the testkit's bound of 200.
    count = max(20, min(200, int(rate_per_minute / 6)))
    return {'count': count, 'ordersPerMinute': rate_per_minute, 'cancelRate': 0.1, 'returnRate': 0.1,
            'declineRate': 0.05, 'outOfStockRate': 0.02}


def measure(w, rate):
    m = {'target_per_s': rate / 60}
    m['placed_per_s'] = scalar(f'sum(rate(commerce_testkit_request_seconds_count{{route="order.place",status="2xx"}}[{w}]))')
    attempted = scalar(f'sum(rate(commerce_testkit_request_seconds_count{{route="order.place"}}[{w}]))')
    failed = count(f'sum(rate(commerce_testkit_request_seconds_count{{route="order.place",status=~"5xx|error"}}[{w}]))')
    m['checkout_failed_pct'] = 100 * failed / attempted if attempted else 0.0
    m['catalog_p95_ms'] = 1000 * p95('route="catalog.list"', w)
    m['place_p95_ms'] = 1000 * p95('route="order.place"', w)
    m['order_get_p95_ms'] = 1000 * p95('route="order.get"', w)
    m['confirmed_p95_s'] = p95('outcome="CONFIRMED"', w, 'commerce_order_saga_seconds')
    m['authz_p95_ms'] = 1000 * scalar(
        f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{service="identity",uri="/internal/authz"}}[{w}])))')
    m['service_5xx_pct'] = 100 * scalar(
        f'sum(rate(http_server_requests_seconds_count{{job="services",status=~"5.."}}[{w}])) '
        f'/ sum(rate(http_server_requests_seconds_count{{job="services"}}[{w}]))')
    m['lag_max'] = by(f'max by (group) (max_over_time(commerce_kafka_consumer_lag{{group=~"{"|".join(SERVICES[1:])}"}}[{w}]))', 'group')
    m['pool_pending_max'] = by(f'max by (service) (max_over_time(hikaricp_connections_pending{{job="services"}}[{w}]))', 'service')
    m['jvm_cpu_cores'] = by(f'sum by (service) (avg_over_time(process_cpu_usage{{job="services"}}[{w}]) * on (instance) group_left system_cpu_count{{job="services"}})', 'service')
    m['busy_skips_per_s'] = count(f'sum(rate(commerce_testkit_shopper_actions_total{{outcome="skipped_busy"}}[{w}]))')
    m['container_cpu'] = container_cpu()
    return m


def fmt(v, digits=0):
    return '—' if v != v else f'{v:.{digits}f}'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--steps', default='60,180,360,600,900,1200', help='orders per minute, comma-separated')
    parser.add_argument('--ramp', type=int, default=60, help='seconds to settle at each step before measuring')
    parser.add_argument('--measure', type=int, default=180, help='seconds measured at each step')
    args = parser.parse_args()
    steps = [int(s) for s in args.steps.split(',')]
    w = f'{args.measure}s'

    sign_in()
    before = call('GET', f'{T}/switches')
    call('PUT', f'{T}/test-data', {'on': True})
    while not call('GET', f'{T}/status')['seeded']:
        time.sleep(3)
    results = []
    started = datetime.datetime.now(datetime.timezone.utc)
    try:
        for rate in steps:
            call('PUT', f'{T}/shoppers', shoppers(rate))
            print(f'step {rate}/min ({rate / 60:.1f}/s): ramp {args.ramp} s, measure {args.measure} s', flush=True)
            time.sleep(args.ramp + args.measure)
            m = measure(w, rate)
            results.append(m)
            print(f"  placed {fmt(m['placed_per_s'], 1)}/s  catalog p95 {fmt(m['catalog_p95_ms'])} ms  "
                  f"place p95 {fmt(m['place_p95_ms'])} ms  confirmed p95 {fmt(m['confirmed_p95_s'], 2)} s  "
                  f"authz p95 {fmt(m['authz_p95_ms'], 1)} ms  failed {fmt(m['checkout_failed_pct'], 1)}%  "
                  f"max lag {max(m['lag_max'].values(), default=0):.0f}  busy {fmt(m['busy_skips_per_s'], 2)}/s",
                  flush=True)
    finally:
        call('PUT', f'{T}/switches', before)
    report(started, args, results)


def report(started, args, results):
    out = ROOT / 'build/load'
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"baseline-{started.strftime('%Y-%m-%dT%H%M')}.md"
    lines = [f'## Load baseline, {started.strftime("%Y-%m-%d %H:%M")} UTC', '',
             f'DEV stack, ramp {args.ramp} s and measure {args.measure} s per step; customer-side latencies are '
             'measured by the simulated shoppers through the storefront edge.', '',
             '| Target orders/s | Placed/s | Catalog p95 ms | POST /orders p95 ms | Order GET p95 ms | '
             'Placed → confirmed p95 s | Auth check p95 ms | Checkouts failed % | Service 5xx % | Max lag | '
             'Testkit busy skips/s |',
             '|---|---|---|---|---|---|---|---|---|---|---|']
    for m in results:
        lines.append(f"| {m['target_per_s']:.1f} | {fmt(m['placed_per_s'], 1)} | {fmt(m['catalog_p95_ms'])} | "
                     f"{fmt(m['place_p95_ms'])} | {fmt(m['order_get_p95_ms'])} | {fmt(m['confirmed_p95_s'], 2)} | "
                     f"{fmt(m['authz_p95_ms'], 1)} | {fmt(m['checkout_failed_pct'], 1)} | "
                     f"{fmt(m['service_5xx_pct'], 2)} | {max(m['lag_max'].values(), default=0):.0f} | "
                     f"{fmt(m['busy_skips_per_s'], 2)} |")
    lines += ['', '### Per step: lag, DB pool waits, CPU', '']
    for m in results:
        top = sorted(m['container_cpu'].items(), key=lambda kv: -kv[1])[:6]
        lines.append(f"- **{m['target_per_s']:.1f}/s** — lag {json.dumps({k: int(v) for k, v in m['lag_max'].items()})}; "
                     f"pool waits {json.dumps({k: int(v) for k, v in m['pool_pending_max'].items() if v})}; "
                     f"JVM CPU cores {json.dumps({k: round(v, 2) for k, v in m['jvm_cpu_cores'].items()})}; "
                     f"busiest containers (cores) {', '.join(f'{k} {v:.2f}' for k, v in top)}")
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))
    print(f'\nload-baseline: report in {path.relative_to(ROOT)}')


if __name__ == '__main__':
    # The report has arrows and dashes; a Windows console is not UTF-8 by default.
    sys.stdout.reconfigure(encoding='utf-8')
    sys.exit(main())
