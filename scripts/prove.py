#!/usr/bin/env python3
"""Runs resilience experiments against a running DEV stack and reports their verdicts.

    scripts/prove.sh                      # the whole catalogue (about an hour)
    scripts/prove.sh --quick              # replica crash, broker crash, poison data, intruder
    scripts/prove.sh flood intruder       # the named experiments

Experiments run one at a time in the controller; this script queues them through
the ops console's API, follows them, prints each verdict with its evidence and
writes a Markdown report per run to build/proofs/. It exits non-zero unless every
experiment passed: one that could not run (not in steady state, or needing the HA
topology on a --lite stack) did not pass.
"""
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV = dict(line.split('=', 1) for line in (ROOT / 'deploy/env/dev.env').read_text().splitlines()
           if '=' in line and not line.startswith('#'))
CONSOLE = f"http://localhost:{ENV.get('OPS_CONSOLE_PORT', '8082')}"
QUICK = ['replica-crash-order', 'broker-crash', 'poison-data', 'intruder']
REPORTS = ROOT / 'build/proofs'
token = None


def call(method, path, body=None, raw=False):
    global token
    for attempt in range(30):
        request = urllib.request.Request(
            CONSOLE + path, method=method, data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json', 'X-Commerce-Client': 'web',
                     **({'Authorization': f'Bearer {token}'} if token else {})})
        try:
            with urllib.request.urlopen(request, timeout=60) as r:
                data = r.read()
                return data.decode() if raw else json.loads(data)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if (method == 'GET' or path == '/api/v1/auth/login') and not isinstance(e, urllib.error.HTTPError):
                time.sleep(10)
                continue
            if not isinstance(e, urllib.error.HTTPError):
                raise
            if e.code == 401 and attempt == 0 and path != '/api/v1/auth/login':
                login()
                continue
            # While an experiment stops a database or a broker, the console's own
            # reads can fail for a moment: wait, as an operator would, up to 5 minutes.
            if method == 'GET' and e.code in (502, 503, 504):
                time.sleep(10)
                continue
            raise SystemExit(f'{method} {path}: {e.code} {e.read().decode(errors="replace")[:500]}')
    raise SystemExit(f'{method} {path}: the console did not answer for 5 minutes')


def login():
    global token
    token = None
    token = call('POST', '/api/v1/auth/login', {'email': ENV['BOOTSTRAP_ADMIN_EMAIL'],
                                                 'password': ENV['DEV_ADMIN_PASSWORD']})['accessToken']


def status():
    """The latest run of each experiment, newest first; queues nothing."""
    data = call('GET', '/api/v1/ops/experiments/runs')
    latest = {}
    for run in data['runs']:
        latest.setdefault(run['experiment'], run)
    counts = {}
    for run in sorted(latest.values(), key=lambda r: r['startedAt']):
        counts[run['verdict']] = counts.get(run['verdict'], 0) + 1
        print(f"{run['verdict']:<15} {run['title']:<40} {run['startedAt'][:19]}")
        for c in run['checks']:
            if not c['passed']:
                print(f"    FAIL {c['name']}: {(c['detail'] or '')[:300]}")
    print('prove --status: ' + ', '.join(f'{n} {v.lower()}' for v, n in sorted(counts.items())))
    return 0


def main(args):
    login()
    if args == ['--status']:
        return status()
    catalogue = [e['name'] for e in call('GET', '/api/v1/ops/experiments')]
    names = QUICK if args == ['--quick'] else (args or catalogue)
    unknown = [n for n in names if n not in catalogue]
    if unknown:
        raise SystemExit(f"Unknown experiments: {', '.join(unknown)}. The catalogue: {', '.join(catalogue)}")
    queued = call('POST', '/api/v1/ops/experiments/run', {'names': names})
    ids = [r['id'] for r in queued]
    print(f'Queued {len(ids)} experiments; they run one at a time.', flush=True)
    REPORTS.mkdir(parents=True, exist_ok=True)
    done = {}
    while len(done) < len(ids):
        time.sleep(10)
        runs = {r['id']: r for r in call('GET', '/api/v1/ops/experiments/runs')['runs']}
        for run_id in ids:
            run = runs.get(run_id)
            if run_id in done or run is None or run['verdict'] == 'RUNNING':
                continue
            done[run_id] = run
            took = ''
            if run.get('endedAt'):
                took = f" in {time.strftime('%M:%S', time.gmtime(_seconds(run)))}"
            print(f"{run['verdict']:<15} {run['title']}{took}", flush=True)
            for c in run['checks']:
                print(f"    {'ok  ' if c['passed'] else 'FAIL'} {c['name']}: {c['detail'] or ''}", flush=True)
            if not run['checks']:
                print(f"    {run['timeline'][-2]['text'] if len(run['timeline']) > 1 else ''}", flush=True)
            (REPORTS / f"{run['experiment']}-{run_id}.md").write_text(
                call('GET', f'/api/v1/ops/experiments/runs/{run_id}/report', raw=True), encoding='utf-8')
    passed = sum(1 for r in done.values() if r['verdict'] == 'PASSED')
    print(f'prove: {passed} of {len(ids)} experiments passed; reports in {REPORTS.relative_to(ROOT)}')
    return 0 if passed == len(ids) else 1


def _seconds(run):
    from datetime import datetime
    parse = lambda s: datetime.fromisoformat(s.replace('Z', '+00:00'))
    return (parse(run['endedAt']) - parse(run['startedAt'])).total_seconds()


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
