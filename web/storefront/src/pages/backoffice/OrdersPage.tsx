import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { backoffice, money, STATUS_WORDS, type Order, type OrderStatus, type Page, type OrderSummary } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';
import { OrderHistory, OrderLines, OrderProgress } from '../shop/OrderView';
import { BackofficeNav } from './BackofficeNav';

const STATUSES: OrderStatus[] = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'RETURN_REQUESTED', 'RETURNED', 'REFUNDED', 'CANCELLED'];

export default function BackofficeOrdersPage() {
  const { id } = useParams();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [list, setList] = useState<Page<OrderSummary> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [query, setQuery] = useState({ search: '', status: '' });

  useEffect(() => {
    backoffice.orders(query.search, query.status, 0).then(setList).catch(setError);
  }, [query]);

  function submit(e: FormEvent) {
    e.preventDefault();
    setQuery({ search: search.trim(), status });
  }

  return (
    <div className="card card-wide">
      <h1>Back office</h1>
      <BackofficeNav />
      <form className="button-row toolbar" onSubmit={submit} role="search">
        <input aria-label="Order id or customer email" placeholder="Order id or customer email" value={search} onChange={(e) => setSearch(e.target.value)} />
        <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="">Any status</option>
          {STATUSES.map((s) => <option key={s} value={s}>{STATUS_WORDS[s]}</option>)}
        </select>
        <button type="submit">Find orders</button>
      </form>
      <ErrorMessage error={error} />
      {id && <OrderPanel id={id} />}
      {list && (
        <div className="table-scroll">
          <table>
            <thead><tr><th scope="col">Placed</th><th scope="col">Order</th><th scope="col">Items</th><th scope="col">Total</th><th scope="col">Status</th></tr></thead>
            <tbody>
              {list.items.map((o) => (
                <tr key={o.id}>
                  <td>{new Date(o.createdAt).toLocaleString()}</td>
                  <td><Link to={`/backoffice/orders/${o.id}`}>{o.id}</Link></td>
                  <td>{o.items}</td><td>{money(o.totalCents, o.currency)}</td>
                  <td><span className={`status status-${o.status.toLowerCase()}`}>{STATUS_WORDS[o.status]}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="hint">{list.total} orders match.</p>
        </div>
      )}
    </div>
  );
}

function OrderPanel({ id }: { id: string }) {
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    setOrder(null);
    backoffice.order(id).then(setOrder).catch(setError);
  }, [id]);
  if (error) return <ErrorMessage error={error} />;
  if (!order) return <p className="hint">Loading…</p>;
  return (
    <section className="detail-panel" aria-label={`Order ${order.id}`}>
      <h2>Order {order.id}</h2>
      <OrderProgress order={order} />
      <OrderLines order={order} />
      <h3>History</h3>
      <OrderHistory order={order} />
    </section>
  );
}
