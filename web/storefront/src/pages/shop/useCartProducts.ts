import { useEffect, useState } from 'react';
import { shop, type Product } from '../../api/shop';
import { useCart } from '../../cart/CartContext';

/** The products in the cart, read fresh (prices and stock change). A product no longer sold comes back null. */
export function useCartProducts() {
  const { lines } = useCart();
  const [products, setProducts] = useState<Record<string, Product | null>>({});
  const skus = lines.map((l) => l.sku).sort().join(',');

  useEffect(() => {
    let cancelled = false;
    Promise.all(
      skus
        .split(',')
        .filter(Boolean)
        .map((sku) => shop.product(sku).then((p) => [sku, p] as const).catch(() => [sku, null] as const)),
    ).then((entries) => {
      if (!cancelled) setProducts(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [skus]);

  return products;
}
