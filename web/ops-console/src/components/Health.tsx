import type { ServiceTile } from '../api/ops';

export type Health = 'good' | 'warning' | 'critical' | 'unknown';

/** Health of a service as the overview judges it. Thresholds mirror the alert rules. */
export function healthOf(s: ServiceTile): Health {
  if (!s.up) return 'critical';
  if ((s.errorRatio ?? 0) > 0.05 || (s.outboxOldestSeconds ?? 0) > 30) return 'critical';
  if ((s.errorRatio ?? 0) > 0.01 || (s.p95Seconds ?? 0) > 1 || (s.lag ?? 0) > 1000 || s.firingIncidents > 0)
    return 'warning';
  return 'good';
}

const WORDS: Record<Health, string> = { good: 'Healthy', warning: 'Degraded', critical: 'Down', unknown: 'Unknown' };
const GLYPHS: Record<Health, string> = { good: '●', warning: '▲', critical: '■', unknown: '○' };

/** A status is never colour alone: a shape and a word go with it. */
export function HealthBadge({ health, label }: { health: Health; label?: string }) {
  return (
    <span className={`health health-${health}`}>
      <span aria-hidden="true" className="health-glyph">
        {GLYPHS[health]}
      </span>
      {label ?? WORDS[health]}
    </span>
  );
}
