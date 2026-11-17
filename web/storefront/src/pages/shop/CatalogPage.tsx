import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { shop, type Page, type Product } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';
import { ProductTile } from './ProductTile';

export default function CatalogPage() {
  const [params, setParams] = useSearchParams();
  const category = params.get('category') ?? '';
  const search = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0);
  const [categories, setCategories] = useState<string[]>([]);
  const [products, setProducts] = useState<Page<Product> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [text, setText] = useState(search);

  useEffect(() => {
    shop.categories().then(setCategories).catch(() => undefined);
  }, []);

  useEffect(() => {
    setProducts(null);
    shop
      .products({ category, q: search, page, size: 24 })
      .then((p) => {
        setProducts(p);
        setError(null);
      })
      .catch(setError);
  }, [category, search, page]);

  function set(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [k, v] of Object.entries(next)) {
      if (v) merged.set(k, v);
      else merged.delete(k);
    }
    if (!('page' in next)) merged.delete('page');
    setParams(merged);
  }

  function onSearch(e: FormEvent) {
    e.preventDefault();
    set({ q: text.trim() });
  }

  const pages = products ? Math.ceil(products.total / products.size) : 0;
  return (
    <div className="shop">
      <form className="search" onSubmit={onSearch} role="search">
        <label htmlFor="search" className="visually-hidden">Search products</label>
        <input id="search" type="search" placeholder="Search products" value={text} onChange={(e) => setText(e.target.value)} />
        <button type="submit">Search</button>
      </form>
      <nav className="categories" aria-label="Categories">
        <button type="button" className={!category ? 'chip chip-on' : 'chip'} aria-pressed={!category} onClick={() => set({ category: '' })}>
          Everything
        </button>
        {categories.map((c) => (
          <button key={c} type="button" className={c === category ? 'chip chip-on' : 'chip'} aria-pressed={c === category} onClick={() => set({ category: c })}>
            {c}
          </button>
        ))}
      </nav>
      <ErrorMessage error={error} />
      {!products ? (
        <p className="hint">Loading…</p>
      ) : products.items.length === 0 ? (
        <p className="hint">{search || category ? 'Nothing matches. Try another search or category.' : 'The shop is empty for now.'}</p>
      ) : (
        <div className="grid">
          {products.items.map((p) => <ProductTile key={p.sku} product={p} />)}
        </div>
      )}
      {pages > 1 && (
        <div className="pager">
          <button type="button" className="secondary" disabled={page === 0} onClick={() => set({ page: String(page - 1) })}>Previous</button>
          <span>Page {page + 1} of {pages}</span>
          <button type="button" className="secondary" disabled={page + 1 >= pages} onClick={() => set({ page: String(page + 1) })}>Next</button>
        </div>
      )}
    </div>
  );
}
