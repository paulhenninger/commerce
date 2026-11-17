import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { openLiveFeed, ops, type Tick } from '../api/ops';
import { HealthBadge, healthOf } from '../components/Health';
import { SystemMap } from '../components/SystemMap';
import { duration, num, percent, rate, clock } from '../components/format';
import { usePoll } from '../components/usePoll';

export default function OverviewPage() {
  const { data: overview, error, updatedAt } = usePoll(ops.overview, 2000);
  const [tick, setTick] = useState<Tick | null>(null);
  const [events, setEvents] = useState<Tick['events']>([]);
  const [connected, setConnected] = useState(false);

  useEffect(
    () =>
      openLiveFeed(
        (t) => {
          setTick(t);
          if (t.events.length) setEvents((e) => [...t.events.slice().reverse(), ...e].slice(0, 60));
        },
        setConnected,
      ),
    [],
  );

  if (!overview) return <p className="muted">{error ?? 'Loading the overview…'}</p>;

  const down = overview.services.filter((s) => !s.up);
  const perSecond = tick ? Object.values(tick.perSecond).reduce((a, b) => a + b, 0) : 0;
  const funnel = overview.funnel;

  return (
    <div className="overview">
      <header className="page-head">
        <h1>Overview</h1>
        <p className="muted">
          {error ? <span className="stale">Not updating: {error}</span> : `Updated ${updatedAt ? new Date(updatedAt).toLocaleTimeString() : ''}`}
        </p>
      </header>

      <section className={`verdict ${down.length || overview.firingIncidents ? 'verdict-bad' : 'verdict-good'}`} aria-live="polite">
        {down.length ? (
          <p>
            <strong>{down.map((s) => s.name).join(', ')}</strong> {down.length === 1 ? 'has' : 'have'} no running instance.
          </p>
        ) : overview.firingIncidents ? (
          <p>
            Every service is running. <Link to="/incidents">{overview.firingIncidents} incident{overview.firingIncidents === 1 ? '' : 's'} firing</Link>.
          </p>
        ) : (
          <p>Every service is running and nothing is firing.</p>
        )}
      </section>

      <div className="overview-grid">
        <SystemMap overview={overview} tick={tick} />

        <section className="panel live" aria-label="Live events">
          <h2>
            Live events <span className="muted">{connected ? `${perSecond}/s` : 'reconnecting…'}</span>
          </h2>
          <ol className="feed">
            {events.length === 0 && <li className="muted">Waiting for events…</li>}
            {events.map((e, i) => (
              <li key={`${e.at}-${i}`} className={e.type === 'dead-letter' ? 'feed-dead' : undefined}>
                <time>{clock(e.at)}</time>
                <span className="feed-type">{e.type}</span>
                <span className="feed-key">{e.key}</span>
              </li>
            ))}
          </ol>
        </section>
      </div>

      <section className="figures" aria-label="System totals">
        <Figure label="Requests per second" value={rate(overview.totals.requestsPerSecond)} />
        <Figure label="Server errors per second" value={rate(overview.totals.errorsPerSecond)} />
        <Figure label="Events processed per second" value={rate(overview.totals.eventsPerSecond)} />
        <Figure label="Orders waiting on stock or payment" value={num(overview.totals.ordersInFlight)} />
        <Figure label="Dead letters, last hour" value={num(overview.totals.deadLetters1h)} />
      </section>

      <section className="panel">
        <h2>Orders, last 15 minutes</h2>
        <div className="funnel">
          {['placed', 'confirmed', 'cancelled', 'returned', 'refunded'].map((k) => (
            <div key={k} className="funnel-step">
              <span className="funnel-n">{num(funnel[k] ?? 0)}</span>
              <span className="muted">{k}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="panel">
        <h2>Services</h2>
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Service</th>
              <th scope="col">Health</th>
              <th scope="col" className="num">Instances</th>
              <th scope="col" className="num">Req/s</th>
              <th scope="col" className="num">Errors</th>
              <th scope="col" className="num">p95</th>
              <th scope="col" className="num">Lag</th>
              <th scope="col" className="num">Outbox oldest</th>
            </tr>
          </thead>
          <tbody>
            {overview.services.map((s) => (
              <tr key={s.name}>
                <td><Link to={`/services/${s.name}`}>{s.name}</Link></td>
                <td><HealthBadge health={healthOf(s)} /></td>
                <td className="num">{s.instances}</td>
                <td className="num">{rate(s.requestsPerSecond)}</td>
                <td className="num">{percent(s.errorRatio)}</td>
                <td className="num">{duration(s.p95Seconds)}</td>
                <td className="num">{num(s.lag)}</td>
                <td className="num">{duration(s.outboxOldestSeconds)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="figure">
      <span className="figure-n">{value}</span>
      <span className="muted">{label}</span>
    </div>
  );
}
