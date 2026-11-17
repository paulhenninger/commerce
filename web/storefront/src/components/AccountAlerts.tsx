import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** Warnings about the signed-in account that belong on every page. */
export function AccountAlerts() {
  const { user, passwordBreached } = useAuth();
  return (
    <>
      {passwordBreached && (
        <p className="alert alert-error" role="alert">
          The password you signed in with appears in a public data breach, so attackers will try it.{' '}
          <Link to="/account">Change it now</Link>.
        </p>
      )}
      {user?.role === 'ADMIN' && !user.mfaEnabled && (
        <p className="alert alert-error" role="alert">
          Administrators must use two-factor authentication or a passkey for the back office.{' '}
          <Link to="/account">Set one up</Link>, then sign in again.
        </p>
      )}
    </>
  );
}
