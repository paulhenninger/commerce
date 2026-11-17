import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { messageOf } from '../api/client';
import { ops, type Incident } from '../api/ops';
import { HealthBadge } from '../components/Health';
import { when } from '../components/format';
import { usePoll } from '../components/usePoll';

const FILTERS: [string | null, string][] = [['FIRING', 'Firing'], ['RESOLVED', 'Resolved'], [null, 'All']];

export default function IncidentsPage() {
  const { id } = useParams();
  const [filter, setFilter] = useState<string | null>('FIRING');
  const { data, error } = usePoll(() => ops.incidents(filter, 0), 5000, [filter]);

  return (
    <div>
      <header className="page-head"><h1>Incidents</h1></header>
      <div className="range" role="group" aria-label="Show">
        {FILTERS.map(([value, label]) => (
          <button key={label} type="button" className={value === filter ? 'chip chip-on' : 'chip'} aria-pressed={value === filter} onClick={() => setFilter(value)}>
            {label}
          </button>
        ))}
      </div>
      {error && <p className="stale">Not updating: {error}</p>}
      <div className={id ? 'split' : undefined}>
        <section className="panel">
          {!data ? (
            <p className="muted">Loading…</p>
          ) : data.items.length === 0 ? (
            <p className="muted">{filter === 'FIRING' ? 'Nothing is firing.' : 'No incidents.'}</p>
          ) : (
            <table className="table">
              <thead><tr><th scope="col">Severity</th><th scope="col">Alert</th><th scope="col">Service</th><th scope="col">Since</th><th scope="col">Owner</th></tr></thead>
              <tbody>
                {data.items.map((i) => (
                  <tr key={i.id} className={String(i.id) === id ? 'row-selected' : undefined}>
                    <td><Severity incident={i} /></td>
                    <td><Link to={`/incidents/${i.id}`}>{i.summary}</Link></td>
                    <td>{i.service ?? '–'}</td>
                    <td>{when(i.startsAt)}</td>
                    <td>{i.acknowledgedBy ?? <span className="muted">nobody yet</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
        {id && <IncidentDetail id={Number(id)} />}
      </div>
    </div>
  );
}

function Severity({ incident }: { incident: Incident }) {
  if (incident.status === 'RESOLVED') return <HealthBadge health="good" label="resolved" />;
  return <HealthBadge health={incident.severity === 'critical' ? 'critical' : 'warning'} label={incident.severity} />;
}

function IncidentDetail({ id }: { id: number }) {
  const { data, reload } = usePoll(() => ops.incident(id), 5000, [id]);
  const [note, setNote] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const navigate = useNavigate();

  async function acknowledge() {
    try {
      await ops.acknowledge(id);
      setMessage('You are now handling this incident.');
      void reload();
    } catch (e) {
      setMessage(messageOf(e));
    }
  }

  async function addNote(e: FormEvent) {
    e.preventDefault();
    try {
      await ops.note(id, note);
      setNote('');
      void reload();
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  if (!data) return <section className="panel"><p className="muted">Loading…</p></section>;
  return (
    <section className="panel incident" aria-label="Incident">
      <h2>
        {data.summary}{' '}
        <button type="button" className="link-button" onClick={() => navigate('/incidents')}>Close</button>
      </h2>
      <p><Severity incident={data} /> {data.alertname} on {data.service ?? 'the system'}, since {new Date(data.startsAt).toLocaleString()}
        {data.endsAt && `, ended ${new Date(data.endsAt).toLocaleString()}`}.</p>
      {data.description && <p>{data.description}</p>}
      {data.runbookUrl && <p>What to do: <code>{data.runbookUrl}</code></p>}
      {message && <p className="alert" role="status">{message}</p>}
      {data.acknowledgedBy ? (
        <p>Handled by {data.acknowledgedBy} since {when(data.acknowledgedAt)}.</p>
      ) : data.status === 'FIRING' ? (
        <button type="button" onClick={() => void acknowledge()}>Take this incident</button>
      ) : null}
      <h3>Timeline</h3>
      <ol className="timeline">
        <li><time>{new Date(data.startsAt).toLocaleTimeString()}</time> Alert started</li>
        {data.notes.map((n) => (
          <li key={n.id}><time>{new Date(n.at).toLocaleTimeString()}</time> {n.author}: {n.note}</li>
        ))}
        {data.endsAt && <li><time>{new Date(data.endsAt).toLocaleTimeString()}</time> Resolved</li>}
      </ol>
      <form onSubmit={addNote}>
        <label htmlFor="note">Add a note</label>
        <textarea id="note" rows={3} maxLength={4000} required value={note} onChange={(e) => setNote(e.target.value)} />
        <button type="submit">Add note</button>
      </form>
    </section>
  );
}
