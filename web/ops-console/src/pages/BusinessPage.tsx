import { useCallback, useState } from 'react';
import { ops, type Series } from '../api/ops';
import { LineChart } from '../components/LineChart';
import { duration, num, rate } from '../components/format';
import { usePoll } from '../components/usePoll';

export default function BusinessPage() {
  const { data: b, error } = usePoll(ops.business, 5000);
  const [range, setRange] = useState('1h');

  return (
    <div>
      <header className="page-head"><h1>Business</h1></header>
      {error && <p className="stale">Not updating: {error}</p>}
      {b && (
        <>
          <section className="figures" aria-label="Last hour">
            {['placed', 'confirmed', 'cancelled', 'returned', 'refunded'].map((k) => (
              <div key={k} className="figure">
                <span className="figure-n">{num(b.lastHour[k] ?? 0)}</span>
                <span className="muted">{k}, last hour</span>
              </div>
            ))}
            <div className="figure">
              <span className="figure-n">{duration(b.sagaSeconds.p95)}</span>
              <span className="muted">order settles, p95 (p50 {duration(b.sagaSeconds.p50)}, p99 {duration(b.sagaSeconds.p99)})</span>
            </div>
          </section>

          <div className="columns">
            <Bars title="Orders now, by status" values={b.byStatus} />
            <Bars title="Cancellations by reason, last hour" values={b.cancellationReasons} />
            <Bars title="Payment results, last hour" values={b.payments} />
            <Bars title="Lowest stock" values={b.lowestStock} ascending />
          </div>
          <section className="panel">
            <h2>Money, last hour</h2>
            <p>
              Revenue {Object.entries(b.revenueLastHour).map(([c, v]) => `${num(v, 2)} ${c}`).join(', ') || '0'}; refunds{' '}
              {Object.entries(b.refundsLastHour).map(([c, v]) => `${num(v, 2)} ${c}`).join(', ') || '0'}.
            </p>
          </section>
        </>
      )}
      <div className="range" role="group" aria-label="Time range">
        {['15m', '1h', '6h', '24h'].map((r) => (
          <button key={r} type="button" className={r === range ? 'chip chip-on' : 'chip'} aria-pressed={r === range} onClick={() => setRange(r)}>{r}</button>
        ))}
      </div>
      <div className="charts">
        <BusinessChart metric="orders" range={range} title="Orders per minute, by outcome" by="outcome" format={rate} />
        <BusinessChart metric="cancellations" range={range} title="Cancellations per minute, by reason" by="reason" format={rate} />
        <BusinessChart metric="saga" range={range} title="Time to settle an order, 95th percentile" format={duration} />
        <BusinessChart metric="revenue" range={range} title="Revenue per minute" format={(v) => num(v, 2)} />
      </div>
    </div>
  );
}

function BusinessChart(props: { metric: string; range: string; title: string; by?: string; format: (v: number) => string }) {
  const { data } = usePoll(() => ops.businessSeries(props.metric, props.range), 10000, [props.metric, props.range]);
  const label = useCallback((s: Series) => (props.by ? s.labels[props.by] ?? '–' : props.title), [props.by, props.title]);
  return <LineChart title={props.title} series={data} label={label} format={props.format} />;
}

/** A ranked list with proportional bars: one series, so no legend; the numbers are printed. */
function Bars({ title, values, ascending }: { title: string; values: Record<string, number | null>; ascending?: boolean }) {
  const rows = Object.entries(values)
    .filter(([, v]) => v !== null && Number.isFinite(v))
    .map(([k, v]) => [k, v as number] as const)
    .sort((a, b) => (ascending ? a[1] - b[1] : b[1] - a[1]));
  const max = Math.max(1, ...rows.map(([, v]) => v));
  return (
    <section className="panel">
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className="muted">Nothing yet.</p>
      ) : (
        <ul className="bars">
          {rows.map(([k, v]) => (
            <li key={k}>
              <span className="bar-label">{k}</span>
              <span className="bar-track"><span className="bar" style={{ width: `${(v / max) * 100}%` }} /></span>
              <span className="num">{num(v)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
