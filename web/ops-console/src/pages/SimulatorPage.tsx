import { useEffect, useState, type FormEvent } from 'react';
import { messageOf } from '../api/client';
import { testkit, type ShopperSettings } from '../api/testkit';
import { clock, num } from '../components/format';
import { usePoll } from '../components/usePoll';

export default function SimulatorPage() {
  const { data: status, error } = usePoll(testkit.status, 2000);
  const [form, setForm] = useState<ShopperSettings | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (status && !form) setForm(status.shoppers.settings);
  }, [status, form]);

  async function apply(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    try {
      await testkit.setShoppers(form);
      setMessage('Applied.');
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  async function switchTestData(on: boolean) {
    try {
      await testkit.setTestData(on);
      setMessage(on ? 'Test data on: seeding, then keeping stock topped up.' : 'Test data off: no more top-ups; what was made stays.');
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  async function switchOff() {
    if (!form) return;
    const off = { ...form, count: 0 };
    setForm(off);
    try {
      await testkit.setShoppers(off);
      setMessage('Shoppers off.');
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  async function stopAll() {
    if (!form) return;
    const stopped = { ...form, ordersPerMinute: 0 };
    setForm(stopped);
    try {
      await testkit.setShoppers(stopped);
      setMessage('Shoppers paused: no new orders.');
    } catch (err) {
      setMessage(messageOf(err));
    }
  }

  const field = (key: keyof ShopperSettings, label: string, max: number, step: number, percent = false) =>
    form && (
      <div className="slider">
        <label htmlFor={key}>{label}</label>
        <input id={key} type="range" min={0} max={max} step={step} value={form[key]}
          onChange={(e) => setForm({ ...form, [key]: Number(e.target.value) })} />
        <span className="num">{percent ? `${Math.round(form[key] * 100)}%` : form[key]}</span>
      </div>
    );

  const counts = status?.shoppers.counts ?? {};
  return (
    <div>
      <header className="page-head"><h1>Simulated shoppers</h1></header>
      {error && <p className="stale">Not updating: {error}</p>}
      {status && !status.adminReady && <p className="alert">The testkit is still creating the administrator.</p>}
      <p className="muted">Every simulation is off until you switch it on here or in the chaos lab; switching off
        stops it and keeps what it made. The switches survive a restart.</p>
      <section className="panel">
        <h2>Test data</h2>
        {status && (
          <p className="button-row">
            <label className="switch">
              <input type="checkbox" checked={status.testData}
                onChange={(e) => void switchTestData(e.target.checked)} />
              <span>{status.testData ? 'On' : 'Off'}</span>
            </label>
            <span className="muted">
              {status.testData
                ? status.seeded ? 'shopper@example.com, 24 products, stock kept topped up' : 'Seeding…'
                : status.seeded ? 'Seeded earlier; stock is no longer topped up' : 'No shopper account or products yet'}
            </span>
          </p>
        )}
      </section>
      <div className="split">
        <section className="panel">
          <h2>Traffic</h2>
          {status && status.shoppers.settings.count > 0 && !status.testData && (
            <p className="alert">Shoppers buy what the catalog has: switch test data on, or add products in the back office.</p>
          )}
          <form onSubmit={apply}>
            {field('count', 'Shoppers', 100, 1)}
            {field('ordersPerMinute', 'Orders per minute (all shoppers)', 600, 5)}
            {field('cancelRate', 'Cancel after confirmation', 1, 0.05, true)}
            {field('returnRate', 'Return after delivery', 1, 0.05, true)}
            {field('declineRate', 'Pay with a card that is declined', 1, 0.05, true)}
            {field('outOfStockRate', 'Try to buy the sold-out product', 1, 0.05, true)}
            <p className="button-row">
              <button type="submit">Apply</button>
              <button type="button" className="secondary" onClick={() => void stopAll()}>Pause orders</button>
              <button type="button" className="secondary" onClick={() => void switchOff()}>Switch off</button>
              {message && <span role="status">{message}</span>}
            </p>
          </form>
        </section>
        <section className="panel">
          <h2>Shoppers now</h2>
          {status && (
            <div className="figures compact">
              <div className="figure"><span className="figure-n">{status.shoppers.ready}</span><span className="muted">signed in</span></div>
              <div className="figure"><span className="figure-n">{status.shoppers.creating}</span><span className="muted">signing up</span></div>
              <div className="figure"><span className="figure-n">{status.shoppers.inFlight}</span><span className="muted">orders being followed</span></div>
            </div>
          )}
          <table className="table">
            <thead><tr><th scope="col">What they did</th><th scope="col" className="num">Times</th></tr></thead>
            <tbody>
              {Object.entries(counts).sort().map(([k, v]) => (
                <tr key={k}><td>{k.replace('.', ': ').replace(/_/g, ' ')}</td><td className="num">{num(v)}</td></tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
      <section className="panel">
        <h2>Journal (latest)</h2>
        <ol className="log">
          {status?.recent.map((e) => (
            <li key={e.seq}>
              <time>{clock(e.at)}</time> {e.shopper} {e.action} {e.orderId ?? ''} <strong>{e.outcome}</strong>
              {e.detail ? <span className="muted"> {e.detail}</span> : null}
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
