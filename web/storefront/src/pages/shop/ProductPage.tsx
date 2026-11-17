import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { money, shop, type Product } from '../../api/shop';
import { useCart } from '../../cart/CartContext';
import { ErrorMessage } from '../../components/ErrorMessage';
import { slug } from './ProductTile';

export default function ProductPage() {
  const { sku = '' } = useParams();
  const [product, setProduct] = useState<Product | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  const { add } = useCart();

  useEffect(() => {
    shop.product(sku).then(setProduct).catch(setError);
  }, [sku]);

  if (error) return <ErrorMessage error={error} />;
  if (!product) return <p className="hint">Loading…</p>;
  const soldOut = product.available <= 0;
  const max = Math.min(20, Math.max(1, product.available));
  return (
    <div className="product">
      <div className={`product-face category-${slug(product.category)}`} aria-hidden="true">
        <span className="tile-name">{product.name}</span>
      </div>
      <div className="product-info">
        <p className="breadcrumb"><Link to="/">Shop</Link> / <Link to={`/?category=${encodeURIComponent(product.category)}`}>{product.category}</Link></p>
        <h1>{product.name}</h1>
        <p className="price large">{money(product.priceCents, product.currency)}</p>
        <p>{product.description}</p>
        <p className={soldOut ? 'stock-out' : 'stock-in'}>
          {soldOut ? 'Sold out' : product.available < 10 ? `Only ${product.available} left` : 'In stock'}
        </p>
        {!soldOut && (
          <div className="buy">
            <label htmlFor="quantity">Quantity</label>
            <input id="quantity" type="number" min={1} max={max} value={quantity}
              onChange={(e) => setQuantity(Math.max(1, Math.min(max, Number(e.target.value) || 1)))} />
            <button type="button" onClick={() => { add(product.sku, quantity); setAdded(true); }}>Add to cart</button>
          </div>
        )}
        {added && <p className="alert alert-success" role="status">Added. <Link to="/cart">View your cart</Link>.</p>}
      </div>
    </div>
  );
}
