import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useOpsConfig } from './OpsConfig';

export function Rail() {
  const { config } = useOpsConfig();
  const { user, signOut } = useAuth();
  const env = config?.environment ?? '?';
  return (
    <nav className="rail" aria-label="Console">
      <div className={`env env-${env}`} title={`This console shows the ${env} environment`}>
        <span className="env-name">{env}</span>
        <span className="env-caption">Commerce operations</span>
      </div>
      <ul className="rail-links">
        <li><NavLink to="/" end>Overview</NavLink></li>
        <li><NavLink to="/incidents">Incidents</NavLink></li>
        <li><NavLink to="/instances">Instances</NavLink></li>
        <li><NavLink to="/kafka">Kafka</NavLink></li>
        <li><NavLink to="/business">Business</NavLink></li>
      </ul>
      {config?.testkit && (
        <>
          <p className="rail-section">Test tools (DEV)</p>
          <ul className="rail-links">
            <li><NavLink to="/simulator">Simulated shoppers</NavLink></li>
            <li><NavLink to="/chaos">Chaos lab</NavLink></li>
          </ul>
        </>
      )}
      <p className="rail-section">Services</p>
      <ul className="rail-links rail-services">
        {config?.services.map((s) => (
          <li key={s}><NavLink to={`/services/${s}`}>{s}</NavLink></li>
        ))}
      </ul>
      <div className="rail-foot">
        {config && (
          <a href={config.grafanaUrl} target="_blank" rel="noreferrer">Open Grafana</a>
        )}
        <span className="rail-user">{user?.email}</span>
        <button type="button" className="link-button" onClick={() => void signOut()}>Sign out</button>
      </div>
    </nav>
  );
}
