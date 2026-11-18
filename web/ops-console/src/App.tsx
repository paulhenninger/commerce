import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Rail } from './components/Rail';
import { OpsConfigProvider, useOpsConfig } from './components/OpsConfig';
import BusinessPage from './pages/BusinessPage';
import InstancesPage from './pages/InstancesPage';
import ProofsPage from './pages/ProofsPage';
import ChaosPage from './pages/ChaosPage';
import SimulatorPage from './pages/SimulatorPage';
import IncidentsPage from './pages/IncidentsPage';
import KafkaPage from './pages/KafkaPage';
import LoginPage from './pages/LoginPage';
import OverviewPage from './pages/OverviewPage';
import ServicePage from './pages/ServicePage';

export default function App() {
  const { loading, user } = useAuth();

  if (loading) {
    return <div className="boot">Connecting…</div>;
  }
  if (!user) {
    return (
      <main className="login-shell">
        <Routes>
          <Route path="*" element={<LoginPage />} />
        </Routes>
      </main>
    );
  }
  if (user.role !== 'ADMIN') {
    return (
      <main className="login-shell">
        <div className="card auth-card">
          <h1>Administrators only</h1>
          <p>You are signed in as {user.email}, which is not an administrator account.</p>
        </div>
      </main>
    );
  }
  return (
    <OpsConfigProvider>
      <Console />
    </OpsConfigProvider>
  );
}

function Console() {
  const { config, error } = useOpsConfig();
  if (error) {
    return (
      <main className="login-shell">
        <div className="card auth-card">
          <h1>The console cannot load</h1>
          <p>{error}</p>
        </div>
      </main>
    );
  }
  if (!config) {
    return <div className="boot">Loading…</div>;
  }
  return (
    <div className="console">
      <Rail />
      <main id="main" className="page" tabIndex={-1}>
        <Routes>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/services/:name" element={<ServicePage />} />
          <Route path="/kafka" element={<KafkaPage />} />
          <Route path="/instances" element={<InstancesPage />} />
          <Route path="/proofs" element={<ProofsPage />} />
          <Route path="/business" element={<BusinessPage />} />
          <Route path="/incidents" element={<IncidentsPage />} />
          <Route path="/incidents/:id" element={<IncidentsPage />} />
          {config.testkit && <Route path="/simulator" element={<SimulatorPage />} />}
          {config.testkit && <Route path="/chaos" element={<ChaosPage />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
