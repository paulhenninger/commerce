import { NavLink } from 'react-router-dom';

export function BackofficeNav() {
  return (
    <nav className="tabs" aria-label="Back office">
      <NavLink to="/backoffice/products" className="tab">Products</NavLink>
      <NavLink to="/backoffice/stock" className="tab">Stock</NavLink>
      <NavLink to="/backoffice/orders" className="tab">Orders</NavLink>
      <NavLink to="/admin" className="tab">Users</NavLink>
    </nav>
  );
}
