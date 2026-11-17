import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Overview, ServiceTile, Tick } from '../api/ops';
import { healthOf, type Health } from './Health';

/**
 * The system as a diagram: customers reach the edges, the edges reach the
 * services, the services talk only through Kafka. Each node takes its health;
 * events from the live feed pulse along the line from the service that wrote
 * them to Kafka, so cause and effect are visible as they happen.
 */

const OWNER: Record<string, string> = {
  order: 'order',
  inventory: 'inventory',
  payment: 'billing',
  shipment: 'shipping',
  product: 'catalog',
  'dead-letter': 'kafka',
};

const W = 920;
const H = 340;

interface Node {
  id: string;
  x: number;
  y: number;
  label: string;
  health: Health;
  detail: string;
  href?: string;
}

export function SystemMap({ overview, tick }: { overview: Overview; tick: Tick | null }) {
  const navigate = useNavigate();
  const [pulses, setPulses] = useState<{ id: number; from: string }[]>([]);

  useEffect(() => {
    if (!tick || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const writers = new Set<string>();
    for (const type of Object.keys(tick.perSecond)) {
      const owner = OWNER[type.split('.')[0]] ?? OWNER[type];
      if (owner && owner !== 'kafka') writers.add(owner);
    }
    if (!writers.size) return;
    const now = Date.now();
    setPulses((p) => [...p.filter((x) => now - x.id < 1200), ...[...writers].map((from, i) => ({ id: now + i, from }))]);
  }, [tick]);

  const nodes = useMemo(() => layout(overview), [overview]);
  const byId = Object.fromEntries(nodes.map((n) => [n.id, n]));
  const services = overview.services.filter((s) => s.name !== 'identity' && s.name !== 'ops');

  const links: [string, string][] = [
    ['customers', 'storefront'],
    ['storefront', 'identity'],
    ...['catalog', 'order', 'inventory', 'billing', 'shipping', 'notification']
      .filter((s) => byId[s])
      .filter((s) => ['catalog', 'order', 'inventory', 'billing'].includes(s))
      .map((s) => ['storefront', s] as [string, string]),
    ...services.map((s) => [s.name, 'kafka'] as [string, string]),
    ['ops-console', 'ops'],
    ['ops', 'kafka'],
  ];

  return (
    <figure className="map">
      <figcaption className="chart-title">System map</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="System map: each part of the system and its health">
        {links
          .filter(([a, b]) => byId[a] && byId[b])
          .map(([a, b]) => {
            const p = byId[a];
            const q = byId[b];
            const worst = rank(p.health) > rank(q.health) ? p.health : q.health;
            return (
              <path
                key={`${a}-${b}`}
                id={`link-${a}-${b}`}
                d={curve(p, q)}
                className={`map-link map-link-${worst === 'critical' ? 'critical' : 'ok'}`}
              />
            );
          })}
        {pulses.map((pulse) =>
          byId[pulse.from] ? (
            <circle key={pulse.id} r={4} className="map-pulse">
              <animateMotion dur="1.1s" repeatCount="1" fill="freeze" path={curve(byId[pulse.from], byId.kafka)} />
            </circle>
          ) : null,
        )}
        {nodes.map((n) => (
          <g
            key={n.id}
            className={`map-node map-${n.health}${n.href ? ' map-clickable' : ''}`}
            transform={`translate(${n.x},${n.y})`}
            onClick={n.href ? () => navigate(n.href!) : undefined}
            onKeyDown={n.href ? (e) => e.key === 'Enter' && navigate(n.href!) : undefined}
            tabIndex={n.href ? 0 : undefined}
            role={n.href ? 'link' : undefined}
            aria-label={`${n.label}: ${n.detail}`}
          >
            <rect x={-62} y={-20} width={124} height={40} rx={6} />
            <text className="map-label" y={-3} textAnchor="middle">{n.label}</text>
            <text className="map-detail" y={12} textAnchor="middle">{n.detail}</text>
          </g>
        ))}
      </svg>
    </figure>
  );
}

function rank(h: Health) {
  return { good: 0, unknown: 1, warning: 2, critical: 3 }[h];
}

function curve(p: { x: number; y: number }, q: { x: number; y: number }) {
  const mx = (p.x + q.x) / 2;
  return `M${p.x + (q.x > p.x ? 62 : -62)},${p.y} C${mx},${p.y} ${mx},${q.y} ${q.x + (q.x > p.x ? -62 : 62)},${q.y}`;
}

function layout(o: Overview): Node[] {
  const tile = (name: string) => o.services.find((s) => s.name === name);
  const probe = (name: string): Health =>
    o.probes[name] === undefined || o.probes[name] === null ? 'unknown' : o.probes[name] === 1 ? 'good' : 'critical';
  const svc = (s: ServiceTile | undefined, x: number, y: number): Node | null =>
    s
      ? {
          id: s.name,
          x,
          y,
          label: s.name,
          health: healthOf(s),
          detail: s.up ? `${s.instances} up, ${s.requestsPerSecond ? s.requestsPerSecond.toFixed(1) : '0'} req/s` : 'no instance',
          href: `/services/${s.name}`,
        }
      : null;
  const domain = ['catalog', 'order', 'inventory', 'billing', 'shipping', 'notification'];
  const nodes: (Node | null)[] = [
    { id: 'customers', x: 80, y: 120, label: 'Customers', health: 'good', detail: 'browsers' },
    { id: 'storefront', x: 250, y: 120, label: 'storefront-web', health: probe('storefront'), detail: 'edge' },
    svc(tile('identity'), 250, 220),
    ...domain.map((name, i) => svc(tile(name), 480, 30 + i * 47)),
    { id: 'kafka', x: 760, y: 150, label: 'Kafka', health: 'good', detail: 'event backbone', href: '/kafka' },
    { id: 'ops-console', x: 250, y: 305, label: 'ops-web', health: probe('ops-console'), detail: 'edge' },
    svc(tile('ops'), 480, 305),
  ];
  return nodes.filter((n): n is Node => n !== null);
}
