import { CANCEL_REASONS, money, STATUS_WORDS, type Order, type OrderStatus } from '../../api/shop';

/**
 * Where an order is in its life, as a sequence of steps the customer can read.
 * Shared by the customer's order page and the back office.
 */
const HAPPY: OrderStatus[] = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPED', 'DELIVERED'];
const RETURN: OrderStatus[] = ['RETURN_REQUESTED', 'RETURNED', 'REFUNDED'];

export function OrderProgress({ order }: { order: Order }) {
  if (order.status === 'CANCELLED') {
    return (
      <div className="progress-cancelled" role="status">
        <strong>Cancelled.</strong> {CANCEL_REASONS[order.cancelReason ?? ''] ?? ''}
      </div>
    );
  }
  const steps = RETURN.includes(order.status) ? [...HAPPY, ...RETURN] : HAPPY;
  const at = steps.indexOf(order.status);
  return (
    <ol className="progress" aria-label="Order progress">
      {steps.map((s, i) => (
        <li key={s} className={i < at ? 'done' : i === at ? 'now' : 'todo'} aria-current={i === at ? 'step' : undefined}>
          {STATUS_WORDS[s]}
        </li>
      ))}
    </ol>
  );
}

export function OrderLines({ order }: { order: Order }) {
  return (
    <div className="table-scroll">
      <table>
        <thead><tr><th scope="col">Product</th><th scope="col">Quantity</th><th scope="col">Price</th></tr></thead>
        <tbody>
          {order.lines.map((l) => (
            <tr key={l.sku}><td>{l.name}</td><td>{l.quantity}</td><td>{money(l.unitPriceCents * l.quantity, order.currency)}</td></tr>
          ))}
          <tr><td colSpan={2}><strong>Total</strong></td><td><strong>{money(order.totalCents, order.currency)}</strong></td></tr>
        </tbody>
      </table>
    </div>
  );
}

export function OrderHistory({ order }: { order: Order }) {
  return (
    <ol className="history">
      {order.history.map((h, i) => (
        <li key={i}><time>{new Date(h.at).toLocaleString()}</time> {h.detail}</li>
      ))}
    </ol>
  );
}
