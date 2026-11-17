import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { shop, type Order } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';
import { OrderHistory, OrderLines, OrderProgress } from './OrderView';

/** States the order leaves on its own soon: worth watching. */
const MOVING = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPED', 'RETURN_REQUESTED', 'RETURNED'];
const CANCELLABLE = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED'];

export default function OrderDetailPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [returning, setReturning] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let stopped = false;
    const load = async () => {
      try {
        const o = await shop.order(id);
        if (stopped) return;
        setOrder(o);
        setError(null);
        // Keep watching while the order is still moving, quickly at first.
        if (MOVING.includes(o.status)) timer = setTimeout(load, document.hidden ? 10000 : 2000);
      } catch (e) {
        if (!stopped) setError(e);
      }
    };
    void load();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [id]);

  async function cancel() {
    setBusy(true);
    setActionError(null);
    try {
      setOrder(await shop.cancel(id));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
    }
  }

  async function submitReturn(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      setOrder(await shop.requestReturn(id, reason.trim()));
      setReturning(false);
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  }

  if (error) return <ErrorMessage error={error} />;
  if (!order) return <p className="hint">Loading your order…</p>;
  const justPlaced = (location.state as { justPlaced?: boolean } | null)?.justPlaced;
  return (
    <div className="card card-wide">
      <p className="breadcrumb"><Link to="/orders">Your orders</Link></p>
      <h1>Order {order.id.slice(-8)}</h1>
      {justPlaced && order.status === 'PENDING' && <p className="alert alert-success" role="status">Thank you. We are checking stock and taking payment now.</p>}
      <OrderProgress order={order} />
      {order.trackingNumber && <p>Tracking number: <strong>{order.trackingNumber}</strong></p>}
      {order.returnTrackingNumber && <p>Return tracking number: <strong>{order.returnTrackingNumber}</strong></p>}
      <ErrorMessage error={actionError} />
      <div className="button-row">
        {CANCELLABLE.includes(order.status) && (
          <button type="button" className="danger" disabled={busy} onClick={() => void cancel()}>Cancel order</button>
        )}
        {order.status === 'DELIVERED' && !returning && (
          <button type="button" className="secondary" onClick={() => setReturning(true)}>Return this order</button>
        )}
      </div>
      {returning && (
        <form onSubmit={submitReturn} className="return-form">
          <label htmlFor="reason">Why are you returning it?</label>
          <input id="reason" required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          <p className="hint">We will email you a return label. You are refunded when the parcel reaches us.</p>
          <div className="button-row">
            <button type="submit" disabled={busy}>Request the return</button>
            <button type="button" className="secondary" onClick={() => setReturning(false)}>Keep it</button>
          </div>
        </form>
      )}
      <h2>Items</h2>
      <OrderLines order={order} />
      <h2>Deliver to</h2>
      <p className="address">{order.shippingAddress.name}<br />{order.shippingAddress.line1}<br />{order.shippingAddress.city} {order.shippingAddress.postalCode}<br />{order.shippingAddress.country}</p>
      <h2>History</h2>
      <OrderHistory order={order} />
    </div>
  );
}
