import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

/**
 * The cart lives in the browser (architecture, section 15): a list of SKUs and
 * quantities. Prices are never stored here — they are read fresh, and the order
 * service prices the order itself. Storage can be unavailable (private windows,
 * blocked site data): the cart then simply lasts as long as the page.
 */

export interface CartLine {
  sku: string;
  quantity: number;
}

interface Cart {
  lines: CartLine[];
  count: number;
  add: (sku: string, quantity?: number) => void;
  setQuantity: (sku: string, quantity: number) => void;
  remove: (sku: string) => void;
  clear: () => void;
}

const KEY = 'commerce.cart.v1';
const MAX_PER_LINE = 20;
const Context = createContext<Cart | null>(null);

function load(): CartLine[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as CartLine[];
    return Array.isArray(parsed)
      ? parsed.filter((l) => typeof l.sku === 'string' && Number.isInteger(l.quantity) && l.quantity > 0)
      : [];
  } catch {
    return [];
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>(load);

  useEffect(() => {
    try {
      localStorage.setItem(KEY, JSON.stringify(lines));
    } catch {
      // Not persisted; the in-memory cart still works.
    }
  }, [lines]);

  const setQuantity = useCallback((sku: string, quantity: number) => {
    setLines((current) => {
      const q = Math.max(0, Math.min(MAX_PER_LINE, Math.floor(quantity)));
      const rest = current.filter((l) => l.sku !== sku);
      if (q === 0) return rest;
      const existing = current.find((l) => l.sku === sku);
      return existing ? current.map((l) => (l.sku === sku ? { sku, quantity: q } : l)) : [...rest, { sku, quantity: q }];
    });
  }, []);

  const add = useCallback(
    (sku: string, quantity = 1) =>
      setLines((current) => {
        const existing = current.find((l) => l.sku === sku);
        const q = Math.min(MAX_PER_LINE, (existing?.quantity ?? 0) + quantity);
        return existing ? current.map((l) => (l.sku === sku ? { sku, quantity: q } : l)) : [...current, { sku, quantity: q }];
      }),
    [],
  );

  const remove = useCallback((sku: string) => setLines((current) => current.filter((l) => l.sku !== sku)), []);
  const clear = useCallback(() => setLines([]), []);

  const value = useMemo(
    () => ({ lines, count: lines.reduce((n, l) => n + l.quantity, 0), add, setQuantity, remove, clear }),
    [lines, add, setQuantity, remove, clear],
  );
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useCart(): Cart {
  const ctx = useContext(Context);
  if (!ctx) throw new Error('useCart must be used inside CartProvider');
  return ctx;
}
