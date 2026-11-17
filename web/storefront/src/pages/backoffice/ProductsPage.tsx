import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { backoffice, money, type Page, type Product, type ProductInput } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';
import { BackofficeNav } from './BackofficeNav';

const EMPTY: ProductInput = { name: '', description: '', category: '', priceCents: 0, currency: 'USD', imageKey: null, active: true };

export default function ProductsPage() {
  const [search, setSearch] = useState('');
  const [list, setList] = useState<Page<Product> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [editing, setEditing] = useState<{ sku: string; isNew: boolean; input: ProductInput; price: string } | null>(null);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(() => {
    backoffice.products(search, 0).then(setList).catch(setError);
  }, [search]);
  useEffect(load, [load]);

  function edit(p: Product | null) {
    setSaved(null);
    setSaveError(null);
    setEditing(p
      ? { sku: p.sku, isNew: false, input: { name: p.name, description: p.description, category: p.category, priceCents: p.priceCents, currency: p.currency, imageKey: p.imageKey, active: p.active }, price: (p.priceCents / 100).toFixed(2) }
      : { sku: '', isNew: true, input: EMPTY, price: '' });
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    setSaveError(null);
    const cents = Math.round(Number(editing.price) * 100);
    try {
      await backoffice.saveProduct(editing.sku.trim().toUpperCase(), { ...editing.input, priceCents: cents });
      setSaved(`Saved ${editing.sku.toUpperCase()}.`);
      setEditing(null);
      load();
    } catch (err) {
      setSaveError(err);
    }
  }

  return (
    <div className="card card-wide">
      <h1>Back office</h1>
      <BackofficeNav />
      <div className="button-row toolbar">
        <input aria-label="Search products" placeholder="Search by name" value={search} onChange={(e) => setSearch(e.target.value)} />
        <button type="button" onClick={() => edit(null)}>Add a product</button>
      </div>
      {saved && <p className="alert alert-success" role="status">{saved} Set its stock on the Stock tab.</p>}
      <ErrorMessage error={error} />
      {editing && (
        <form className="detail-panel" onSubmit={save}>
          <h2>{editing.isNew ? 'New product' : `Edit ${editing.sku}`}</h2>
          {editing.isNew && (
            <>
              <label htmlFor="sku">Product code (SKU)</label>
              <input id="sku" required pattern="[A-Za-z0-9][A-Za-z0-9-]{0,39}" title="Up to 40 letters, digits and hyphens" value={editing.sku}
                onChange={(e) => setEditing({ ...editing, sku: e.target.value })} />
            </>
          )}
          <label htmlFor="name">Name</label>
          <input id="name" required maxLength={200} value={editing.input.name} onChange={(e) => setEditing({ ...editing, input: { ...editing.input, name: e.target.value } })} />
          <label htmlFor="category">Category</label>
          <input id="category" required maxLength={64} value={editing.input.category} onChange={(e) => setEditing({ ...editing, input: { ...editing.input, category: e.target.value } })} />
          <label htmlFor="description">Description</label>
          <input id="description" maxLength={4000} value={editing.input.description} onChange={(e) => setEditing({ ...editing, input: { ...editing.input, description: e.target.value } })} />
          <label htmlFor="price">Price ({editing.input.currency})</label>
          <input id="price" required inputMode="decimal" pattern="[0-9]+(\.[0-9]{1,2})?" title="A price such as 12.50" value={editing.price}
            onChange={(e) => setEditing({ ...editing, price: e.target.value })} />
          <label className="choice">
            <input type="checkbox" checked={editing.input.active} onChange={(e) => setEditing({ ...editing, input: { ...editing.input, active: e.target.checked } })} /> On sale
          </label>
          <ErrorMessage error={saveError} />
          <div className="button-row">
            <button type="submit">Save product</button>
            <button type="button" className="secondary" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </form>
      )}
      {list && (list.items.length === 0 ? (
        <p className="hint">No products yet. Add the first one.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead><tr><th scope="col">SKU</th><th scope="col">Name</th><th scope="col">Category</th><th scope="col">Price</th><th scope="col">Available</th><th scope="col">On sale</th><th scope="col" /></tr></thead>
            <tbody>
              {list.items.map((p) => (
                <tr key={p.sku}>
                  <td>{p.sku}</td><td>{p.name}</td><td>{p.category}</td><td>{money(p.priceCents, p.currency)}</td><td>{p.available}</td>
                  <td>{p.active ? 'Yes' : 'No'}</td>
                  <td className="row-actions"><button type="button" onClick={() => edit(p)}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}
