import { Link, useNavigate } from 'react-router-dom';
import { money } from '../../api/shop';
import { useCart } from '../../cart/CartContext';
import { useCartProducts } from './useCartProducts';

export default function CartPage() {
  const { lines, setQuantity, remove } = useCart();
  const products = useCartProducts();
  const navigate = useNavigate();

  if (lines.length === 0) {
    return (
      <div className="card">
        <h1>Your cart is empty</h1>
        <p><Link to="/">Browse the shop</Link> and add something you like.</p>
      </div>
    );
  }
  const unavailable = lines.filter((l) => products[l.sku] === null);
  const priced = lines.filter((l) => products[l.sku]);
  const currency = priced[0] ? products[priced[0].sku]!.currency : 'USD';
  const total = priced.reduce((sum, l) => sum + products[l.sku]!.priceCents * l.quantity, 0);
  const loading = lines.some((l) => products[l.sku] === undefined);

  return (
    <div className="card card-wide">
      <h1>Your cart</h1>
      <div className="table-scroll">
        <table>
          <thead><tr><th scope="col">Product</th><th scope="col">Price</th><th scope="col">Quantity</th><th scope="col">Subtotal</th><th scope="col" /></tr></thead>
          <tbody>
            {lines.map((l) => {
              const p = products[l.sku];
              return (
                <tr key={l.sku}>
                  <td>{p ? <Link to={`/products/${l.sku}`}>{p.name}</Link> : p === null ? `${l.sku} (no longer sold)` : '…'}</td>
                  <td>{p ? money(p.priceCents, p.currency) : '–'}</td>
                  <td>
                    <input aria-label={`Quantity of ${p?.name ?? l.sku}`} className="qty" type="number" min={1} max={20}
                      value={l.quantity} onChange={(e) => setQuantity(l.sku, Number(e.target.value) || 1)} />
                  </td>
                  <td>{p ? money(p.priceCents * l.quantity, p.currency) : '–'}</td>
                  <td className="row-actions"><button type="button" onClick={() => remove(l.sku)}>Remove</button></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="total">Total <strong>{money(total, currency)}</strong></p>
      {unavailable.length > 0 && <p className="alert alert-error">Remove the products that are no longer sold to continue.</p>}
      <div className="button-row">
        <button type="button" disabled={loading || unavailable.length > 0} onClick={() => navigate('/checkout')}>Check out</button>
        <Link to="/" className="button-link">Keep shopping</Link>
      </div>
    </div>
  );
}
