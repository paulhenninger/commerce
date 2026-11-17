import { useCallback, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ops, type Series } from '../api/ops';
import { HealthBadge } from '../components/Health';
import { LineChart } from '../components/LineChart';
import { bytes, duration, num, rate } from '../components/format';
import { usePoll } from '../components/usePoll';

const RANGES = ['15m', '1h', '6h', '24h'];

export default function ServicePage() {
  const { name = '' } = useParams();
  const [range, setRange] = useState('1h');
  const { data: detail, error } = usePoll(() => ops.service(name), 5000, [name]);
  const { data: errors } = usePoll(() => ops.errors(name), 15000, [name]);

  return (
    <div>
      <header className="page-head">
        <h1>{name}</h1>
        {detail && (
          <p className="links">
            <a href={detail.links.dashboard} target="_blank" rel="noreferrer">Dashboard</a>
            <a href={detail.links.logs} target="_blank" rel="noreferrer">Logs</a>
            <a href={detail.links.traces} target="_blank" rel="noreferrer">Traces</a>
          </p>
        )}
      </header>
      {error && <p className="stale">Not updating: {error}</p>}

      {detail && (
        <section className="figures">
          <div className="figure">
            <span className="figure-n">{detail.instances.filter((i) => i.up).length}</span>
            <span className="muted">instances up</span>
          </div>
          <div className="figure">
            <span className="figure-n">{num(detail.outboxPending)}</span>
            <span className="muted">events waiting to publish</span>
          </div>
          <div className="figure">
            <span className="figure-n">{duration(detail.outboxOldestSeconds)}</span>
            <span className="muted">oldest unpublished</span>
          </div>
          <div className="figure">
            <span className="figure-n">{num(Object.values(detail.lagByTopic).reduce<number>((a, b) => a + (b ?? 0), 0))}</span>
            <span className="muted">events behind (consumer lag)</span>
          </div>
        </section>
      )}

      {detail && (
        <section className="panel">
          <h2>Instances</h2>
          {detail.instances.length === 0 ? (
            <p><HealthBadge health="critical" label="No instance is running" /></p>
          ) : (
            <table className="table">
              <thead>
                <tr><th scope="col">Instance</th><th scope="col">State</th><th scope="col" className="num">Up for</th><th scope="col" className="num">Heap</th></tr>
              </thead>
              <tbody>
                {detail.instances.map((i) => (
                  <tr key={i.instance}>
                    <td>{i.instance}</td>
                    <td><HealthBadge health={i.up ? 'good' : 'critical'} label={i.up ? 'Up' : 'Not answering'} /></td>
                    <td className="num">{duration(i.uptimeSeconds)}</td>
                    <td className="num">{bytes(i.heapBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {Object.keys(detail.breakers).length > 0 && (
            <p>
              Circuit breakers:{' '}
              {Object.keys(detail.breakers).map((b) => {
                const [n, state] = b.split(':');
                return (
                  <HealthBadge key={b} health={state === 'closed' ? 'good' : state === 'open' ? 'critical' : 'warning'} label={`${n} ${state}`} />
                );
              })}
            </p>
          )}
        </section>
      )}

      <div className="range" role="group" aria-label="Time range">
        {RANGES.map((r) => (
          <button key={r} type="button" className={r === range ? 'chip chip-on' : 'chip'} aria-pressed={r === range} onClick={() => setRange(r)}>
            {r}
          </button>
        ))}
      </div>
      <div className="charts">
        <ServiceChart name={name} metric="rate" range={range} title="Requests per second" format={rate} />
        <ServiceChart name={name} metric="errors" range={range} title="Server errors per second" format={rate} />
        <ServiceChart name={name} metric="p95" range={range} title="Response time, 95th percentile" format={duration} />
        <ServiceChart name={name} metric="events" range={range} title="Events consumed per second, by outcome" format={rate} by="outcome" />
        <ServiceChart name={name} metric="lag" range={range} title="Consumer lag by topic" format={(v) => num(v)} by="topic" />
        <ServiceChart name={name} metric="heap" range={range} title="Heap by instance" format={bytes} by="instance" />
        <ServiceChart name={name} metric="dbpool" range={range} title="Database connections" format={(v) => num(v)} by="state" />
        <ServiceChart name={name} metric="threads" range={range} title="Live threads by instance" format={(v) => num(v)} by="instance" />
      </div>

      <section className="panel">
        <h2>Recent errors (last hour)</h2>
        {!errors ? (
          <p className="muted">Loading…</p>
        ) : errors.length === 0 ? (
          <p className="muted">No errors logged in the last hour.</p>
        ) : (
          <ol className="log">
            {errors.map((e, i) => (
              <li key={i}>
                <time>{new Date(e.at).toLocaleTimeString()}</time> {summarize(e.line)}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

function ServiceChart(props: { name: string; metric: string; range: string; title: string; format: (v: number) => string; by?: string }) {
  const { data } = usePoll(() => ops.serviceSeries(props.name, props.metric, props.range), 10000, [props.name, props.metric, props.range]);
  const label = useCallback((s: Series) => (props.by ? s.labels[props.by] ?? '–' : props.title), [props.by, props.title]);
  return <LineChart title={props.title} series={data} label={label} format={props.format} />;
}

function summarize(line: string): string {
  try {
    const j = JSON.parse(line) as { message?: string; error?: { type?: string; message?: string }; log?: { logger?: string } };
    const parts = [j.message, j.error?.type, j.error?.message].filter(Boolean);
    return parts.join(' — ');
  } catch {
    return line.slice(0, 400);
  }
}
