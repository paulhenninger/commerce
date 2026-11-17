import { useState } from 'react';
import { messageOf } from '../api/client';
import { ops, type DeadLetter } from '../api/ops';
import { HealthBadge } from '../components/Health';
import { traceLink, useOpsConfig } from '../components/OpsConfig';
import { num, when } from '../components/format';
import { usePoll } from '../components/usePoll';

export default function KafkaPage() {
  const { data: cluster, error } = usePoll(ops.kafka, 5000);
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <div>
      <header className="page-head">
        <h1>Kafka</h1>
      </header>
      {error && <p className="stale">Not updating: {error}</p>}
      {!cluster ? (
        <p className="muted">Loading…</p>
      ) : (
        <>
          <section className="panel">
            <h2>Brokers</h2>
            <table className="table">
              <thead><tr><th scope="col">Broker</th><th scope="col">Address</th><th scope="col">Role</th><th scope="col" className="num">Partitions led</th></tr></thead>
              <tbody>
                {cluster.brokers.map((b) => (
                  <tr key={b.id}>
                    <td>{b.id}</td>
                    <td>{b.host}:{b.port}</td>
                    <td>{b.controller ? 'Active controller' : 'Broker'}</td>
                    <td className="num">{b.leaderOf}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Consumer groups</h2>
            <table className="table">
              <thead><tr><th scope="col">Group</th><th scope="col">State</th><th scope="col" className="num">Members</th><th scope="col">Lag by topic</th></tr></thead>
              <tbody>
                {cluster.groups.map((g) => {
                  const total = Object.values(g.lagByTopic).reduce((a, b) => a + b, 0);
                  return (
                    <tr key={g.group}>
                      <td>{g.group}</td>
                      <td>
                        <HealthBadge health={g.state === 'STABLE' && total < 1000 ? 'good' : g.members === 0 ? 'critical' : 'warning'} label={g.state.toLowerCase()} />
                      </td>
                      <td className="num">{g.members}</td>
                      <td>
                        {Object.entries(g.lagByTopic).map(([t, l]) => (
                          <span key={t} className="lag">{t.replace('commerce.', '')} <strong className="num">{num(l)}</strong></span>
                        ))}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Topics</h2>
            <table className="table">
              <thead><tr><th scope="col">Topic</th><th scope="col" className="num">Partitions</th><th scope="col" className="num">Records kept</th><th scope="col" className="num">Under-replicated</th><th scope="col" /></tr></thead>
              <tbody>
                {cluster.topics.map((t) => (
                  <tr key={t.name} className={t.deadLetter && t.records > 0 ? 'row-alert' : undefined}>
                    <td>{t.name}</td>
                    <td className="num">{t.partitions}</td>
                    <td className="num">{num(t.records)}</td>
                    <td className="num">{t.underReplicated}</td>
                    <td>
                      {t.deadLetter && t.records > 0 && (
                        <button type="button" className="secondary small" onClick={() => setSelected(t.name)}>
                          Browse dead letters
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {selected && <DeadLetters topic={selected} onClose={() => setSelected(null)} />}
        </>
      )}
    </div>
  );
}

function DeadLetters({ topic, onClose }: { topic: string; onClose: () => void }) {
  const { data, error, reload } = usePoll(() => ops.deadLetters(topic), 30000, [topic]);
  const { config } = useOpsConfig();
  const [open, setOpen] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);

  async function replay(d: DeadLetter) {
    setResult(null);
    try {
      const r = await ops.replay(d.topic, d.partition, d.offset);
      setResult(`Replayed offset ${d.offset} to ${r.to} (now offset ${r.newOffset}).`);
      void reload();
    } catch (e) {
      setResult(`Not replayed: ${messageOf(e)}`);
    } finally {
      setConfirming(null);
    }
  }

  return (
    <section className="panel" aria-label={`Dead letters on ${topic}`}>
      <h2>
        Dead letters on {topic}{' '}
        <button type="button" className="link-button" onClick={onClose}>Close</button>
      </h2>
      {result && <p className="alert" role="status">{result}</p>}
      {error && <p className="stale">{error}</p>}
      {!data ? (
        <p className="muted">Loading…</p>
      ) : (
        <ol className="dead-letters">
          {data.map((d) => {
            const id = `${d.partition}-${d.offset}`;
            const traceId = d.traceparent?.split('-')[1];
            return (
              <li key={id}>
                <button type="button" className="dl-head" aria-expanded={open === id} onClick={() => setOpen(open === id ? null : id)}>
                  <span className="num">#{d.offset}</span>
                  <span>{when(d.at)}</span>
                  <span className="dl-reason">{d.reason ?? d.exception ?? 'No reason recorded'}</span>
                </button>
                {open === id && (
                  <div className="dl-body">
                    <dl>
                      <dt>Key</dt><dd>{d.key ?? '–'}</dd>
                      <dt>From</dt><dd>{d.originalTopic ?? '?'} offset {d.originalOffset ?? '?'}, group {d.consumerGroup ?? '?'}</dd>
                      <dt>Exception</dt><dd>{d.exception ?? '–'}</dd>
                      <dt>Trace</dt>
                      <dd>{traceId && config ? <a href={traceLink(config.grafanaUrl, traceId)} target="_blank" rel="noreferrer">{traceId}</a> : '–'}</dd>
                    </dl>
                    <pre className="payload">{d.payload}{d.truncated ? '\n… (truncated)' : ''}</pre>
                    {confirming === id ? (
                      <p className="button-row">
                        <span>Publish this record again to {d.originalTopic ?? topic.replace(/\.dlt$/, '')}?</span>
                        <button type="button" onClick={() => void replay(d)}>Replay</button>
                        <button type="button" className="secondary" onClick={() => setConfirming(null)}>Cancel</button>
                      </p>
                    ) : (
                      <button type="button" className="secondary" onClick={() => setConfirming(id)}>Replay…</button>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
