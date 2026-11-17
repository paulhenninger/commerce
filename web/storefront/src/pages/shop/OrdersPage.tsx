import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { money, shop, STATUS_WORDS, type Page, type OrderSummary } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';

export default function OrdersPage() {
  const [page, setPage] = useState(0);
  const [orders, setOrders] = useState<Page<OrderSummary> | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    shop.orders(page).then(setOrders).catch(setError);
  }, [page]);

  if (error) return <ErrorMessage error={error} />;
  if (!orders) return <p className="hint">Loading…</p>;
  if (orders.total === 0) {
    return <div className="card"><h1>Your orders</h1><p>You have not ordered anything yet. <Link to="/">Visit the shop</Link>.</p></div>;
  }
  const pages = Math.ceil(orders.total / orders.size);
  return (
    <div className="card card-wide">
      <h1>Your orders</h1>
      <div className="table-scroll">
        <table>
          <thead><tr><th scope="col">Placed</th><th scope="col">Order</th><th scope="col">Items</th><th scope="col">Total</th><th scope="col">Status</th></tr></thead>
          <tbody>
            {orders.items.map((o) => (
              <tr key={o.id}>
                <td>{new Date(o.createdAt).toLocaleString()}</td>
                <td><Link to={`/orders/${o.id}`}>{o.id.slice(-8)}</Link></td>
                <td>{o.items}</td>
                <td>{money(o.totalCents, o.currency)}</td>
                <td><span className={`status status-${o.status.toLowerCase()}`}>{STATUS_WORDS[o.status]}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="pager">
          <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {pages}</span>
          <button type="button" className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </div>
  );
}
