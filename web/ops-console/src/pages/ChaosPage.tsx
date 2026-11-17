import { useState, type FormEvent } from 'react';
import { messageOf } from '../api/client';
import { CONSUMERS, EXPECTED, PRODUCERS, TOPICS, testkit, type Fault } from '../api/testkit';
import { HealthBadge } from '../components/Health';
import { num, when } from '../components/format';
import { usePoll } from '../components/usePoll';

export default function ChaosPage() {
  const { data: status, error, reload } = usePoll(testkit.status, 2000);
  const [kind, setKind] = useState<'producer' | 'consumer'>('producer');
  const [type, setType] = useState('MALFORMED');
  const [topic, setTopic] = useState(TOPICS[0]);
  const [rate, setRate] = useState(10);
  const [message, setMessage] = useState<string | null>(null);

  async function start(e: FormEvent) {
    e.preventDefault();
    try {
      await testkit.start(kind, type, topic, rate);
      setMessage(null);
      void reload();
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  const types = kind === 'producer' ? PRODUCERS : CONSUMERS;
  const violations = Object.entries(status?.contractViolations ?? {});
  const real = violations.filter(([k]) => k.startsWith('real '));

  return (
    <div>
      <header className="page-head"><h1>Chaos lab</h1></header>
      {error && <p className="stale">Not updating: {error}</p>}
      <div className="split">
        <section className="panel">
          <h2>Start a problem actor</h2>
          <form onSubmit={start}>
            <div className="range" role="group" aria-label="Kind">
              {(['producer', 'consumer'] as const).map((k) => (
                <button key={k} type="button" className={k === kind ? 'chip chip-on' : 'chip'} aria-pressed={k === kind}
                  onClick={() => { setKind(k); setType(k === 'producer' ? 'MALFORMED' : 'SLOW'); }}>
                  {k === 'producer' ? 'Bad producer' : 'Bad consumer'}
                </button>
              ))}
            </div>
            <label htmlFor="type">Behaviour</label>
            <select id="type" value={type} onChange={(e) => setType(e.target.value)}>
              {types.map(([t, d]) => <option key={t} value={t}>{t.toLowerCase().replace('_', ' ')}: {d}</option>)}
            </select>
            <p className="hint">Expected: {EXPECTED[type]}</p>
            <label htmlFor="topic">Topic</label>
            <select id="topic" value={topic} onChange={(e) => setTopic(e.target.value)}>
              {TOPICS.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
            {kind === 'producer' && (
              <>
                <label htmlFor="rate">Records per second</label>
                <input id="rate" type="number" min={1} max={2000} value={rate} onChange={(e) => setRate(Number(e.target.value))} />
              </>
            )}
            <p className="button-row">
              <button type="submit">Start</button>
              {message && <span className="stale" role="status">{message}</span>}
            </p>
          </form>
        </section>
        <section className="panel">
          <h2>Contracts</h2>
          {real.length === 0 ? (
            <p><HealthBadge health="good" label="No real service has written an invalid event" /></p>
          ) : (
            <p><HealthBadge health="critical" label={`${real.length} kinds of invalid event from real services`} /></p>
          )}
          <table className="table">
            <thead><tr><th scope="col">Producer, topic, type</th><th scope="col" className="num">Invalid</th></tr></thead>
            <tbody>
              {violations.map(([k, v]) => <tr key={k}><td>{k}</td><td className="num">{num(v)}</td></tr>)}
            </tbody>
          </table>
          <Faults />
        </section>
      </div>

      <section className="panel">
        <h2>
          Running actors{' '}
          {status && status.actors.length > 0 && (
            <button type="button" className="secondary small" onClick={() => void testkit.stopAll().then(reload)}>Stop all</button>
          )}
        </h2>
        {!status || status.actors.length === 0 ? (
          <p className="muted">Nothing running. The system should be quiet.</p>
        ) : (
          <table className="table">
            <thead><tr><th scope="col">Actor</th><th scope="col">Topic</th><th scope="col">Since</th><th scope="col">What happened</th><th scope="col">Expected</th><th scope="col" /></tr></thead>
            <tbody>
              {status.actors.map((a) => (
                <tr key={a.id}>
                  <td>{a.type.toLowerCase().replace('_', ' ')} {a.kind}{a.kind === 'producer' ? `, ${a.ratePerSecond}/s` : ''}</td>
                  <td>{a.topic.replace('commerce.', '')}</td>
                  <td>{when(a.startedAt)}</td>
                  <td>
                    {Object.entries(a.counts).map(([k, v]) => <span key={k} className="lag">{k} <strong className="num">{num(v)}</strong></span>)}
                    {a.lastError && <div className="muted">{a.lastError.slice(0, 160)}</div>}
                  </td>
                  <td className="muted">{EXPECTED[a.type]}</td>
                  <td><button type="button" className="secondary small" onClick={() => void testkit.stop(a.id).then(reload)}>Stop</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function Faults() {
  const { data, reload } = usePoll(testkit.faults, 5000);
  async function set(target: string, fault: Fault) {
    await testkit.setFault(target, fault);
    void reload();
  }
  return (
    <>
      <h3>External systems</h3>
      {data &&
        Object.entries(data).map(([target, f]) => (
          <div key={target} className="fault">
            <strong>{target === 'payment' ? 'Payment provider' : 'Carrier'}</strong>
            <label className="inline">
              <input type="checkbox" checked={f.outage} onChange={(e) => void set(target, { ...f, outage: e.target.checked })} /> Down
            </label>
            <label className="inline">
              Errors {Math.round(f.errorRate * 100)}%
              <input type="range" min={0} max={1} step={0.1} value={f.errorRate}
                onChange={(e) => void set(target, { ...f, errorRate: Number(e.target.value) })} />
            </label>
            <label className="inline">
              Delay {f.latencyMs} ms
              <input type="range" min={0} max={5000} step={250} value={f.latencyMs}
                onChange={(e) => void set(target, { ...f, latencyMs: Number(e.target.value) })} />
            </label>
          </div>
        ))}
    </>
  );
}
