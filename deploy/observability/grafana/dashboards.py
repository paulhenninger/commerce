#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards (dashboards/*.json).

Edit this file, not the JSON, then run:  python3 deploy/observability/grafana/dashboards.py
Every panel is a PromQL or LogQL query against the provisioned data sources
(uids prometheus, loki, tempo); see docs/architecture.md, section 9.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).parent / 'dashboards'
PROM = {'type': 'prometheus', 'uid': 'prometheus'}
LOKI = {'type': 'loki', 'uid': 'loki'}


class Board:
    def __init__(self, uid, title, description, variables=()):
        self.uid, self.title, self.description = uid, title, description
        self.panels, self.y, self.x, self.row_h = [], 0, 0, 0
        self.variables = list(variables)

    def row(self, title):
        self.newline()
        self.panels.append({'type': 'row', 'title': title, 'collapsed': False,
                            'gridPos': {'x': 0, 'y': self.y, 'w': 24, 'h': 1}, 'id': len(self.panels) + 1})
        self.y += 1

    def newline(self):
        if self.x:
            self.y += self.row_h
            self.x, self.row_h = 0, 0

    def _place(self, panel, w, h):
        if self.x + w > 24:
            self.newline()
        panel['gridPos'] = {'x': self.x, 'y': self.y, 'w': w, 'h': h}
        panel['id'] = len(self.panels) + 1
        self.x += w
        self.row_h = max(self.row_h, h)
        self.panels.append(panel)

    def series(self, title, targets, unit='short', w=12, h=8, stack=False, description=''):
        self._place({
            'type': 'timeseries', 'title': title, 'description': description, 'datasource': PROM,
            'targets': [{'refId': chr(65 + i), 'datasource': PROM, 'expr': e, 'legendFormat': l}
                        for i, (e, l) in enumerate(targets)],
            'fieldConfig': {'defaults': {'unit': unit, 'custom': {
                'lineWidth': 1, 'fillOpacity': 15 if stack else 0,
                'stacking': {'mode': 'normal' if stack else 'none'}}}, 'overrides': []},
            'options': {'legend': {'displayMode': 'list', 'placement': 'bottom'},
                        'tooltip': {'mode': 'multi', 'sort': 'desc'}},
        }, w, h)

    def stat(self, title, expr, unit='short', w=4, h=4, thresholds=None, description=''):
        steps = [{'color': 'green', 'value': None}]
        for value, color in (thresholds or []):
            steps.append({'color': color, 'value': value})
        self._place({
            'type': 'stat', 'title': title, 'description': description, 'datasource': PROM,
            'targets': [{'refId': 'A', 'datasource': PROM, 'expr': expr, 'instant': True}],
            'fieldConfig': {'defaults': {'unit': unit, 'thresholds': {'mode': 'absolute', 'steps': steps}},
                            'overrides': []},
            'options': {'reduceOptions': {'calcs': ['lastNotNull']}, 'colorMode': 'background', 'graphMode': 'none'},
        }, w, h)

    def logs(self, title, expr, w=24, h=10):
        self._place({'type': 'logs', 'title': title, 'datasource': LOKI,
                     'targets': [{'refId': 'A', 'datasource': LOKI, 'expr': expr}],
                     'options': {'showTime': True, 'wrapLogMessage': True, 'sortOrder': 'Descending'}}, w, h)

    def write(self):
        board = {
            'uid': self.uid, 'title': self.title, 'description': self.description, 'tags': ['commerce'],
            'timezone': 'utc', 'schemaVersion': 39, 'version': 1, 'refresh': '10s',
            'time': {'from': 'now-1h', 'to': 'now'}, 'panels': self.panels,
            'templating': {'list': self.variables},
        }
        OUT.mkdir(exist_ok=True)
        (OUT / f'{self.uid}.json').write_text(json.dumps(board, indent=2) + '\n', encoding='utf-8', newline='\n')


SERVICE_VAR = {
    'name': 'service', 'label': 'Service', 'type': 'query', 'datasource': PROM,
    'query': {'query': 'label_values(up{job="services"}, service)', 'refId': 'service'},
    'definition': 'label_values(up{job="services"}, service)', 'refresh': 2, 'current': {'text': 'order', 'value': 'order'},
}

# --------------------------------------------------------------- overview
b = Board('commerce-overview', 'Commerce — System overview',
          'Is everything up, and how is each service doing (rate, errors, duration, saturation)?')
b.row('Health')
b.stat('Services up', 'count(up{job="services"} == 1)', w=4)
b.stat('Services down', 'count(up{job="services"} == 0) or vector(0)', thresholds=[(1, 'red')], w=4)
b.stat('Firing alerts', 'count(ALERTS{alertstate="firing"}) or vector(0)', thresholds=[(1, 'orange')], w=4)
b.stat('5xx (5m)', 'sum(increase(http_server_requests_seconds_count{status=~"5.."}[5m])) or vector(0)',
       thresholds=[(1, 'orange'), (20, 'red')], w=4)
b.stat('Dead letters (1h)', 'sum(increase(commerce_events_dead_lettered_total[1h])) or vector(0)',
       thresholds=[(1, 'orange')], w=4)
b.stat('Outbox oldest', 'max(commerce_outbox_oldest_age_seconds)', unit='s', thresholds=[(5, 'orange'), (30, 'red')], w=4)
b.row('Requests (RED)')
b.series('Requests per second', [('sum by (service) (rate(http_server_requests_seconds_count{job="services"}[1m]))', '{{service}}')], unit='reqps')
b.series('Errors (5xx) per second', [('sum by (service) (rate(http_server_requests_seconds_count{job="services", status=~"5.."}[1m]))', '{{service}}')], unit='reqps')
b.series('p95 latency', [('histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket{job="services"}[5m])))', '{{service}}')], unit='s')
b.series('p99 latency', [('histogram_quantile(0.99, sum by (le, service) (rate(http_server_requests_seconds_bucket{job="services"}[5m])))', '{{service}}')], unit='s')
b.row('Saturation (USE)')
b.series('Heap used', [('sum by (service, instance) (jvm_memory_used_bytes{area="heap"})', '{{service}} {{instance}}')], unit='bytes')
b.series('CPU', [('sum by (service, instance) (process_cpu_usage)', '{{service}} {{instance}}')], unit='percentunit')
b.series('DB connections in use / waiting', [('sum by (service) (hikaricp_connections_active)', '{{service}} active'),
                                              ('sum by (service) (hikaricp_connections_pending)', '{{service}} waiting')])
b.series('Live threads', [('sum by (service) (jvm_threads_live_threads)', '{{service}}')])
b.row('Recent errors')
b.logs('ERROR lines, every service', '{environment=~".+", level="ERROR"}')
b.write()

# ------------------------------------------------------------- order flow
b = Board('commerce-orders', 'Commerce — Order flow',
          'The business: orders placed and how they end, how long the saga takes, money in and out, stock.')
b.row('Funnel')
b.stat('Placed (1h)', 'sum(increase(commerce_orders_total{outcome="placed"}[1h])) or vector(0)')
b.stat('Confirmed (1h)', 'sum(increase(commerce_orders_total{outcome="confirmed"}[1h])) or vector(0)')
b.stat('Cancelled (1h)', 'sum(increase(commerce_orders_total{outcome="cancelled"}[1h])) or vector(0)',
       thresholds=[(1, 'orange')])
b.stat('In flight', 'max(commerce_orders_in_flight)')
b.stat('Oldest in flight', 'max(commerce_orders_oldest_in_flight_age_seconds)', unit='s',
       thresholds=[(120, 'orange'), (240, 'red')])
b.stat('Saga p95 (15m)', 'histogram_quantile(0.95, sum by (le) (rate(commerce_order_saga_seconds_bucket[15m])))', unit='s',
       thresholds=[(10, 'orange'), (30, 'red')])
b.series('Orders by outcome', [('sum by (outcome) (rate(commerce_orders_total[1m])) * 60', '{{outcome}}')], unit='short',
         description='Per minute')
b.series('Cancellations by reason', [('sum by (reason) (rate(commerce_orders_total{outcome="cancelled"}[5m])) * 60', '{{reason}}')],
         stack=True, description='Per minute')
b.series('Orders by status', [('max by (status) (commerce_orders_by_status)', '{{status}}')], stack=True)
b.series('Saga duration', [('histogram_quantile(0.5, sum by (le, outcome) (rate(commerce_order_saga_seconds_bucket[5m])))', 'p50 {{outcome}}'),
                            ('histogram_quantile(0.95, sum by (le, outcome) (rate(commerce_order_saga_seconds_bucket[5m])))', 'p95 {{outcome}}')], unit='s')
b.row('Money and dependencies')
b.series('Revenue and refunds per minute', [('sum by (currency) (rate(commerce_revenue_cents_total[5m])) * 60 / 100', 'revenue {{currency}}'),
                                            ('sum by (currency) (rate(commerce_refunds_cents_total[5m])) * 60 / 100', 'refunds {{currency}}')])
b.series('Payment results', [('sum by (outcome) (rate(commerce_payments_total[5m])) * 60', '{{outcome}}')], stack=True)
b.series('External call latency p95', [('histogram_quantile(0.95, sum by (le, target, operation) (rate(commerce_external_calls_seconds_bucket[5m])))', '{{target}} {{operation}}')], unit='s')
b.series('Circuit breakers (1 = open)', [('max by (name) (resilience4j_circuitbreaker_state{state="open"})', '{{name}}')])
b.series('Emails', [('sum by (template, outcome) (rate(commerce_notifications_total[5m])) * 60', '{{template}} {{outcome}}')])
b.series('Lowest stock', [('bottomk(10, commerce_stock_available)', '{{sku}}')])
b.write()

# -------------------------------------------------------------- messaging
b = Board('commerce-messaging', 'Commerce — Kafka and messaging',
          'Events: what each consumer does with them, what fails, and whether publishing keeps up.')
b.series('Events consumed by outcome', [('sum by (consumer, outcome) (rate(commerce_events_consumed_total[1m]))', '{{consumer}} {{outcome}}')], unit='ops', w=24)
b.series('Handler p95', [('histogram_quantile(0.95, sum by (le, consumer) (rate(commerce_event_processing_seconds_bucket[5m])))', '{{consumer}}')], unit='s')
b.series('Dead-lettered', [('sum by (consumer, topic, reason) (increase(commerce_events_dead_lettered_total[5m]))', '{{consumer}} {{topic}} {{reason}}')])
b.series('Events published', [('sum by (topic) (rate(commerce_outbox_published_total[1m]))', '{{topic}}')], unit='ops')
b.series('Outbox backlog', [('max by (service) (commerce_outbox_pending)', '{{service}} pending'),
                            ('max by (service) (commerce_outbox_oldest_age_seconds)', '{{service}} oldest (s)')])
b.series('Consumer lag (from ops)', [('max by (group, topic) (commerce_kafka_consumer_lag)', '{{group}} {{topic}}')])
b.series('Outbox relay leader', [('max by (service, instance) (commerce_outbox_relay_leader)', '{{service}} {{instance}}')])
b.logs('Dead-lettered records', '{environment=~".+"} |= "dead-letter topic"')
b.write()

# --------------------------------------------------------- service detail
b = Board('commerce-service', 'Commerce — Service detail', 'One service in depth. Pick it at the top.', [SERVICE_VAR])
b.stat('Instances up', 'count(up{job="services", service="$service"} == 1)')
b.stat('Requests/s', 'sum(rate(http_server_requests_seconds_count{service="$service"}[1m]))', unit='reqps')
b.stat('Error ratio', 'sum(rate(http_server_requests_seconds_count{service="$service", status=~"5.."}[5m])) / '
                      'sum(rate(http_server_requests_seconds_count{service="$service"}[5m]))', unit='percentunit',
       thresholds=[(0.001, 'orange'), (0.01, 'red')])
b.stat('p95', 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="$service"}[5m])))', unit='s')
b.stat('Outbox oldest', 'max(commerce_outbox_oldest_age_seconds{service="$service"}) or vector(0)', unit='s')
b.stat('Heap', 'max(jvm_memory_used_bytes{service="$service", area="heap"})', unit='bytes')
b.series('Requests by endpoint', [('sum by (method, uri, status) (rate(http_server_requests_seconds_count{service="$service"}[1m]))', '{{method}} {{uri}} {{status}}')], unit='reqps')
b.series('p95 by endpoint', [('histogram_quantile(0.95, sum by (le, method, uri) (rate(http_server_requests_seconds_bucket{service="$service"}[5m])))', '{{method}} {{uri}}')], unit='s')
b.series('Events consumed', [('sum by (type, outcome) (rate(commerce_events_consumed_total{service="$service"}[1m]))', '{{type}} {{outcome}}')], unit='ops')
b.series('Heap by instance', [('sum by (instance) (jvm_memory_used_bytes{service="$service", area="heap"})', '{{instance}}')], unit='bytes')
b.logs('Logs', '{service="$service"}')
b.write()

print('dashboards written to', OUT)
