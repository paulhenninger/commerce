import { useCallback, useEffect, useState } from 'react';
import { backoffice, type Page, type StockLevel } from '../../api/shop';
import { ErrorMessage } from '../../components/ErrorMessage';
import { BackofficeNav } from './BackofficeNav';

export default function StockPage() {
  const [page, setPage] = useState(0);
  const [stock, setStock] = useState<Page<StockLevel> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(() => {
    backoffice.stock(page).then(setStock).catch(setError);
  }, [page]);
  useEffect(load, [load]);

  async function save(sku: string) {
    setError(null);
    setMessage(null);
    try {
      const level = await backoffice.setStock(sku, Number(edits[sku]));
      setMessage(`${sku}: ${level.onHand} on hand, ${level.available} available.`);
      setEdits(({ [sku]: _, ...rest }) => rest);
      load();
    } catch (e) {
      setError(e);
    }
  }

  const pages = stock ? Math.ceil(stock.total / stock.size) : 0;
  return (
    <div className="card card-wide">
      <h1>Back office</h1>
      <BackofficeNav />
      <p className="hint">On hand is what is in the warehouse; reserved is held for orders not yet paid for. A product appears here once it has been added.</p>
      {message && <p className="alert alert-success" role="status">{message}</p>}
      <ErrorMessage error={error} />
      {stock && (stock.items.length === 0 ? <p className="hint">No products yet.</p> : (
        <div className="table-scroll">
          <table>
            <thead><tr><th scope="col">SKU</th><th scope="col">On hand</th><th scope="col">Reserved</th><th scope="col">Available</th><th scope="col">Set on hand</th></tr></thead>
            <tbody>
              {stock.items.map((s) => (
                <tr key={s.sku}>
                  <td>{s.sku}</td><td>{s.onHand}</td><td>{s.reserved}</td><td>{s.available}</td>
                  <td className="row-actions">
                    <input aria-label={`New on-hand count for ${s.sku}`} className="qty" type="number" min={s.reserved} max={1000000}
                      value={edits[s.sku] ?? ''} placeholder={String(s.onHand)} onChange={(e) => setEdits({ ...edits, [s.sku]: e.target.value })} />
                    <button type="button" disabled={edits[s.sku] === undefined || edits[s.sku] === ''} onClick={() => void save(s.sku)}>Save</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      {pages > 1 && (
        <div className="pager">
          <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {pages}</span>
          <button type="button" className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </div>
  );
}
