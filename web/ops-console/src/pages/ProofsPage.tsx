import { useState } from 'react';
import { messageOf } from '../api/client';
import { ops, type ExperimentRun, type Verdict } from '../api/ops';
import { HealthBadge, type Health } from '../components/Health';
import { clock, duration, when } from '../components/format';
import { usePoll } from '../components/usePoll';

const VERDICT: Record<Verdict, { health: Health; label: string }> = {
  RUNNING: { health: 'unknown', label: 'Running' },
  PASSED: { health: 'good', label: 'Passed' },
  FAILED: { health: 'critical', label: 'Failed' },
  NOT_RUN: { health: 'warning', label: 'Not run' },
  NOT_APPLICABLE: { health: 'unknown', label: 'Needs HA' },
};

function seconds(run: ExperimentRun): number | null {
  if (!run.endedAt) return null;
  return (Date.parse(run.endedAt) - Date.parse(run.startedAt)) / 1000;
}

function download(run: ExperimentRun) {
  const blob = new Blob([JSON.stringify(run, null, 2)], { type: 'application/json' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `experiment-${run.experiment}-${run.id}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
}

/** Resilience proofs (architecture, section 11): run an experiment, read its verdict and its evidence. */
export default function ProofsPage() {
  const catalogue = usePoll(ops.experiments, 60_000);
  const runs = usePoll(ops.runs, 3000);
  const [open, setOpen] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function start(names: string[], what: string) {
    setMessage(null);
    try {
      const queued = await ops.runExperiments(names);
      setMessage(`Queued ${what} (${queued.length} ${queued.length === 1 ? 'run' : 'runs'}). They run one at a time.`);
      void runs.reload();
    } catch (e) {
      setMessage(messageOf(e));
    }
  }

  const latest = new Map<string, ExperimentRun>();
  for (const r of runs.data?.runs ?? []) {
    if (!latest.has(r.experiment)) latest.set(r.experiment, r);
  }
  const queued = (runs.data?.runs ?? []).filter((r) => r.verdict === 'RUNNING').length;

  return (
    <div>
      <header className="page-head">
        <h1>Resilience proofs</h1>
        <p className="muted">
          Each experiment checks steady state, injects one failure under simulated shoppers, removes it, waits for
          everything to settle, then reaches a verdict from what the shoppers saw and an audit of every service's records.
        </p>
      </header>
      {message && <p className="alert" role="status">{message}</p>}
      {(catalogue.error || runs.error) && <p className="stale">Not updating: {catalogue.error ?? runs.error}</p>}

      <section className="panel">
        <h2>
          Catalogue{' '}
          <span className="muted">{queued > 0 ? `${queued} running or queued` : 'idle'}</span>
        </h2>
        <p>
          <button type="button" onClick={() => void start([], 'the whole catalogue')}>Run the whole catalogue</button>
        </p>
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Experiment</th>
              <th scope="col">Last verdict</th>
              <th scope="col" className="num">Failure lasts</th>
              <th scope="col">Run</th>
            </tr>
          </thead>
          <tbody>
            {(catalogue.data ?? []).map((e) => {
              const last = latest.get(e.name);
              return (
                <tr key={e.name}>
                  <td>
                    {e.title}
                    <div className="muted">{e.hypothesis}{e.needsHa ? ' Needs the HA topology.' : ''}</div>
                  </td>
                  <td>
                    {last ? (
                      <>
                        <HealthBadge health={VERDICT[last.verdict].health} label={VERDICT[last.verdict].label} />
                        <div className="muted">{when(last.startedAt)}</div>
                      </>
                    ) : (
                      <span className="muted">Never run</span>
                    )}
                  </td>
                  <td className="num">{duration(e.observeSeconds)}</td>
                  <td>
                    <button type="button" className="small secondary" onClick={() => void start([e.name], e.title)}>
                      Run
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Runs</h2>
        {(runs.data?.runs ?? []).length === 0 && <p className="muted">No experiment has run yet. Run one from the catalogue.</p>}
        <table className="table">
          <tbody>
            {(runs.data?.runs ?? []).map((r) => (
              <tr key={r.id}>
                <td>
                  <button type="button" className="link" aria-expanded={open === r.id}
                    onClick={() => setOpen(open === r.id ? null : r.id)}>
                    {r.title}
                  </button>
                  <div className="muted">{when(r.startedAt)}{seconds(r) !== null ? `, took ${duration(seconds(r))}` : ''}</div>
                  {open === r.id && (
                    <div className="run-detail">
                      <p><em>{r.hypothesis}</em></p>
                      {r.checks.length > 0 && (
                        <table className="table">
                          <thead>
                            <tr><th scope="col">Evidence</th><th scope="col">Result</th><th scope="col">Detail</th></tr>
                          </thead>
                          <tbody>
                            {r.checks.map((c, i) => (
                              <tr key={i}>
                                <td>{c.name}</td>
                                <td><HealthBadge health={c.passed ? 'good' : 'critical'} label={c.passed ? 'Holds' : 'Broken'} /></td>
                                <td>{c.detail}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                      <ol className="timeline">
                        {r.timeline.map((t, i) => <li key={i}><span className="muted">{clock(t.at)}</span> {t.text}</li>)}
                      </ol>
                      <button type="button" className="small secondary" onClick={() => download(r)}>Download this run</button>
                    </div>
                  )}
                </td>
                <td><HealthBadge health={VERDICT[r.verdict].health} label={VERDICT[r.verdict].label} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
