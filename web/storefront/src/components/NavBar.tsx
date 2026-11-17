import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';

export function NavBar() {
  const { user, signOut } = useAuth();
  const { count } = useCart();
  const navigate = useNavigate();

  async function handleSignOut() {
    await signOut();
    navigate('/login');
  }

  return (
    <header className="navbar">
      <Link to="/" className="navbar-brand">
        ⬢ Commerce
      </Link>
      <nav className="navbar-links">
        <NavLink to="/" end>Shop</NavLink>
        <NavLink to="/cart">Cart{count > 0 ? ` (${count})` : ''}</NavLink>
        {user ? (
          <>
            <NavLink to="/orders">Orders</NavLink>
            <NavLink to="/account">Account</NavLink>
            {user.role === 'ADMIN' && <NavLink to="/backoffice">Back office</NavLink>}
            <button type="button" className="link-button" onClick={handleSignOut}>
              Sign out
            </button>
          </>
        ) : (
          <>
            <NavLink to="/login">Sign in</NavLink>
            <NavLink to="/signup">Create account</NavLink>
          </>
        )}
      </nav>
    </header>
  );
}
