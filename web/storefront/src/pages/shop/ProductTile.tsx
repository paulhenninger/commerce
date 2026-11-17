import { Link } from 'react-router-dom';
import { money, type Product } from '../../api/shop';
import { useCart } from '../../cart/CartContext';

/**
 * A product without a photograph is shown by its name, set large on a field
 * coloured by its category: honest about what we have, and scannable.
 */
export function ProductTile({ product }: { product: Product }) {
  const { add } = useCart();
  const soldOut = product.available <= 0;
  return (
    <article className="tile">
      <Link to={`/products/${product.sku}`} className={`tile-face category-${slug(product.category)}`}>
        <span className="tile-category">{product.category}</span>
        <span className="tile-name">{product.name}</span>
      </Link>
      <div className="tile-foot">
        <span className="price">{money(product.priceCents, product.currency)}</span>
        {soldOut ? (
          <span className="stock-out">Sold out</span>
        ) : (
          <button type="button" className="secondary small" onClick={() => add(product.sku)}>
            Add to cart
          </button>
        )}
      </div>
    </article>
  );
}

export function slug(category: string): string {
  return category.toLowerCase().replace(/[^a-z0-9]+/g, '-');
}
