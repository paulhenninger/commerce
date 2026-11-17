import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { money, shop, type Address, type Line } from '../../api/shop';
import { useAuth } from '../../auth/AuthContext';
import { useCart } from '../../cart/CartContext';
import { ErrorMessage } from '../../components/ErrorMessage';
import { useCartProducts } from './useCartProducts';

/**
 * Payment is an opaque token from the payment provider; no card details ever
 * touch this page. Until a provider's widget is wired in, the test tokens the
 * simulated provider understands are offered.
 */
const CARDS: [string, string][] = [
  ['tok_visa', 'Test card that is approved'],
  ['tok_decline', 'Test card that is declined'],
  ['tok_insufficient', 'Test card with insufficient funds'],
];

export default function CheckoutPage() {
  const { lines, clear } = useCart();
  const { user } = useAuth();
  const products = useCartProducts();
  const navigate = useNavigate();
  const [address, setAddress] = useState<Address>({ name: user?.name ?? '', line1: '', city: '', postalCode: '', country: 'US' });
  const [card, setCard] = useState(CARDS[0][0]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [changed, setChanged] = useState<{ lines: Line[]; totalCents: number } | null>(null);
  // One key per checkout attempt: a retried click returns the same order instead of a second one.
  const [attempt, setAttempt] = useState(() => crypto.randomUUID());

  const shown = useMemo(() => {
    let total = 0;
    let currency = 'USD';
    for (const l of lines) {
      const p = products[l.sku];
      if (p) {
        total += p.priceCents * l.quantity;
        currency = p.currency;
      }
    }
    return { total, currency };
  }, [lines, products]);
  const expected = changed ? changed.totalCents : shown.total;

  if (lines.length === 0) {
    return <div className="card"><h1>Nothing to check out</h1><p><Link to="/">Back to the shop</Link></p></div>;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const placed = await shop.placeOrder(
        { lines: lines.map((l) => ({ sku: l.sku, quantity: l.quantity })), shippingAddress: address, paymentMethod: card, expectedTotalCents: expected },
        attempt,
      );
      clear();
      navigate(`/orders/${placed.id}`, { state: { justPlaced: true } });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'PRICE_CHANGED' && err.body) {
        // Prices moved since the cart was filled: show the new total and let the customer confirm it.
        setChanged(err.body as { lines: Line[]; totalCents: number });
        setAttempt(crypto.randomUUID());
      } else {
        setError(err);
      }
    } finally {
      setBusy(false);
    }
  }

  const field = (key: keyof Address, label: string, props: Record<string, unknown> = {}) => (
    <>
      <label htmlFor={key}>{label}</label>
      <input id={key} required value={address[key]} onChange={(e) => setAddress({ ...address, [key]: e.target.value })} {...props} />
    </>
  );

  return (
    <div className="checkout">
      <form className="card" onSubmit={submit}>
        <h1>Check out</h1>
        <h2>Deliver to</h2>
        {field('name', 'Full name', { autoComplete: 'name', maxLength: 100 })}
        {field('line1', 'Address', { autoComplete: 'address-line1', maxLength: 200 })}
        {field('city', 'Town or city', { autoComplete: 'address-level2', maxLength: 100 })}
        {field('postalCode', 'Postcode', { autoComplete: 'postal-code', maxLength: 20 })}
        {field('country', 'Country code', { autoComplete: 'country', pattern: '[A-Z]{2}', maxLength: 2, title: 'Two capital letters, e.g. US' })}
        <h2>Pay with</h2>
        <fieldset className="choices">
          <legend className="visually-hidden">Payment method</legend>
          {CARDS.map(([token, label]) => (
            <label key={token} className="choice">
              <input type="radio" name="card" value={token} checked={card === token} onChange={() => setCard(token)} /> {label}
            </label>
          ))}
        </fieldset>
        {changed && (
          <p className="alert alert-error" role="alert">
            Prices changed since you filled your cart. The total is now <strong>{money(changed.totalCents, shown.currency)}</strong>. Place the order to accept it.
          </p>
        )}
        <ErrorMessage error={error} />
        <button type="submit" disabled={busy}>{busy ? 'Placing your order…' : `Place order, ${money(expected, shown.currency)}`}</button>
      </form>
      <aside className="card summary" aria-label="Order summary">
        <h2>Your order</h2>
        <ul className="plain-list">
          {lines.map((l) => {
            const p = products[l.sku];
            return <li key={l.sku}>{l.quantity} × {p?.name ?? l.sku}</li>;
          })}
        </ul>
        <p className="total">Total <strong>{money(expected, shown.currency)}</strong></p>
      </aside>
    </div>
  );
}
