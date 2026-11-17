import { useState } from 'react';
import { messageOf } from '../api/client';
import { ops, type InstanceRow } from '../api/ops';
import { HealthBadge, type Health } from '../components/Health';
import { useOpsConfig } from '../components/OpsConfig';
import { bytes, duration } from '../components/format';
import { usePoll } from '../components/usePoll';

/** What each action does to an instance, said plainly (architecture, section 11). */
const ACTIONS: { action: string; label: string; when: (r: InstanceRow) => boolean; title: string }[] = [
  { action: 'stop', label: 'Stop', when: (r) => r.state === 'running', title: 'Graceful shutdown: work in flight finishes' },
  { action: 'kill', label: 'Kill', when: (r) => r.state === 'running', title: 'A crash: work in flight is abandoned' },
  { action: 'pause', label: 'Pause', when: (r) => r.state === 'running', title: 'A hang: holds its connections but does nothing' },
  { action: 'unpause', label: 'Resume', when: (r) => r.state === 'paused', title: 'Carry on after a pause' },
  { action: 'restart', label: 'Restart', when: (r) => r.state === 'running', title: 'Stop, then start' },
  { action: 'start', label: 'Start', when: (r) => r.state === 'exited' || r.state === 'created', title: 'Bring it back' },
];

function stateHealth(r: InstanceRow): Health {
  if (r.state === 'paused') return 'warning';
  if (r.state !== 'running') return 'critical';
  if (r.health === 'unhealthy') return 'critical';
  if (r.health === 'starting') return 'warning';
  return 'good';
}

function stateWord(r: InstanceRow): string {
  if (r.state === 'running') return r.health === 'starting' ? 'Starting' : r.health === 'unhealthy' ? 'Unhealthy' : 'Running';
  if (r.state === 'exited') return 'Stopped';
  return r.state.charAt(0).toUpperCase() + r.state.slice(1);
}

export default function InstancesPage() {
  const { config } = useOpsConfig();
  const { data, error, reload } = usePoll(ops.instances, 2000);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function act(r: InstanceRow, action: string) {
    setBusy(`${r.containerId}-${action}`);
    setMessage(null);
    try {
      const after = await ops.act(r.containerId ?? r.name, action);
      setMessage(`${r.name}: ${action} done, now ${after.state}.`);
      void reload();
    } catch (e) {
      setMessage(`${r.name}: ${messageOf(e)}`);
    } finally {
      setBusy(null);
    }
  }

  const groups = new Map<string, InstanceRow[]>();
  for (const r of data?.rows ?? []) {
    const key = r.service.startsWith('kafka') ? 'kafka' : r.service;
    groups.set(key, [...(groups.get(key) ?? []), r]);
  }
  const control = data?.control && config?.instanceControl;

  return (
    <div>
      <header className="page-head">
        <h1>Instances</h1>
        <p className="muted">
          {control ? 'You can stop, kill, pause and restart instances here (DEV only). Every action is recorded as an incident.' : 'Read-only in this environment.'}
        </p>
      </header>
      {error && <p className="stale">Not updating: {error}</p>}
      {message && <p className="alert" role="status">{message}</p>}
      {[...groups.entries()].map(([service, rows]) => (
        <section key={service} className="panel">
          <h2>
            {service === 'kafka' ? 'Kafka brokers' : service}{' '}
            <span className="muted">{rows.filter((r) => r.state === 'running').length} of {rows.length} running</span>
          </h2>
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Instance</th>
                <th scope="col">State</th>
                <th scope="col" className="num">Up for</th>
                <th scope="col" className="num">Heap</th>
                <th scope="col">{service === 'kafka' ? 'Role' : 'Partitions and duties'}</th>
                {control && <th scope="col">Control</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.containerId ?? r.name}>
                  <td>{r.name}<div className="muted">{r.address ?? ''}</div></td>
                  <td><HealthBadge health={stateHealth(r)} label={stateWord(r)} /></td>
                  <td className="num">{duration(r.uptimeSeconds)}</td>
                  <td className="num">{bytes(r.heapBytes)}</td>
                  <td>
                    {r.brokerId !== null && r.brokerId !== undefined ? (
                      <>Broker {r.brokerId}{r.activeController ? ', active controller' : ''}; leads {r.partitionsLed} partitions</>
                    ) : (
                      <>
                        {r.relayLeader && <span className="lag">publishes the outbox</span>}
                        {Object.entries(r.partitions).map(([group, parts]) => (
                          <span key={group} className="lag">{group} partitions {parts.join(', ')}</span>
                        ))}
                      </>
                    )}
                  </td>
                  {control && (
                    <td>
                      {r.controllable ? (
                        <span className="controls">
                          {ACTIONS.filter((a) => a.when(r)).map((a) => (
                            <button key={a.action} type="button" className={`small ${a.action === 'kill' ? 'danger' : 'secondary'}`}
                              title={a.title} disabled={busy !== null} onClick={() => void act(r, a.action)}>
                              {busy === `${r.containerId}-${a.action}` ? '…' : a.label}
                            </button>
                          ))}
                        </span>
                      ) : (
                        <span className="muted">Not controllable</span>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      ))}
    </div>
  );
}
