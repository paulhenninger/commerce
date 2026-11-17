import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { Series } from '../api/ops';

/**
 * A time-series line chart in plain SVG: one y-axis, thin lines, recessive grid,
 * a crosshair with a tooltip of every series' value at that moment, and a legend
 * when there is more than one series. Colours follow the series' name (sorted),
 * never their rank in the current data, so a series keeps its colour as others
 * come and go.
 */
export function LineChart({
  title,
  series,
  label,
  format,
  height = 180,
}: {
  title: string;
  series: Series[] | null;
  label: (s: Series) => string;
  format: (v: number) => string;
  height?: number;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  const [hover, setHover] = useState<number | null>(null);
  const titleId = useId();

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(240, entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const named = useMemo(() => {
    const list = (series ?? []).map((s) => ({ name: label(s), points: s.points }));
    list.sort((a, b) => a.name.localeCompare(b.name));
    // Eight categorical slots; past that the rest share the last as "other".
    return list.map((s, i) => ({ ...s, slot: Math.min(i, 7) + 1 }));
  }, [series, label]);

  const pad = { top: 8, right: 12, bottom: 22, left: 52 };
  const innerW = width - pad.left - pad.right;
  const innerH = height - pad.top - pad.bottom;
  const all = named.flatMap((s) => s.points);
  const xs = all.map((p) => p[0]);
  const ys = all.map((p) => p[1]).filter((v): v is number => v !== null && Number.isFinite(v));
  const x0 = Math.min(...xs);
  const x1 = Math.max(...xs);
  const yMax = ys.length ? Math.max(...ys) * 1.1 || 1 : 1;
  const x = (t: number) => pad.left + (x1 === x0 ? 0 : ((t - x0) / (x1 - x0)) * innerW);
  const y = (v: number) => pad.top + innerH - (v / yMax) * innerH;
  const ticks = [0, 0.5, 1].map((f) => f * yMax);
  const times = [...new Set(xs)].sort((a, b) => a - b);

  function onMove(e: React.PointerEvent<SVGSVGElement>) {
    if (!times.length) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - rect.left;
    let best = times[0];
    for (const t of times) if (Math.abs(x(t) - px) < Math.abs(x(best) - px)) best = t;
    setHover(best);
  }

  const empty = !series || named.length === 0 || ys.length === 0;

  return (
    <figure className="chart" aria-labelledby={titleId}>
      <figcaption id={titleId} className="chart-title">{title}</figcaption>
      <div ref={box} className="chart-box">
        {empty ? (
          <p className="chart-empty" style={{ height }}>{series ? 'No data in this range' : 'Loading…'}</p>
        ) : (
          <svg
            width={width}
            height={height}
            role="img"
            aria-label={`${title}, ${named.length} series`}
            onPointerMove={onMove}
            onPointerLeave={() => setHover(null)}
          >
            {ticks.map((v) => (
              <g key={v}>
                <line className="grid" x1={pad.left} x2={width - pad.right} y1={y(v)} y2={y(v)} />
                <text className="axis" x={pad.left - 6} y={y(v) + 4} textAnchor="end">{format(v)}</text>
              </g>
            ))}
            <text className="axis" x={pad.left} y={height - 4}>{new Date(x0 * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</text>
            <text className="axis" x={width - pad.right} y={height - 4} textAnchor="end">
              {new Date(x1 * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </text>
            {named.map((s) => {
              let d = '';
              let pen = false;
              for (const [t, v] of s.points) {
                if (v === null || !Number.isFinite(v)) {
                  pen = false;
                  continue;
                }
                d += `${pen ? 'L' : 'M'}${x(t).toFixed(1)},${y(v).toFixed(1)}`;
                pen = true;
              }
              return <path key={s.name} d={d} className={`line series-${s.slot}`} />;
            })}
            {hover !== null && (
              <g>
                <line className="crosshair" x1={x(hover)} x2={x(hover)} y1={pad.top} y2={pad.top + innerH} />
                {named.map((s) => {
                  const p = s.points.find((q) => q[0] === hover);
                  return p && p[1] !== null && Number.isFinite(p[1]) ? (
                    <circle key={s.name} cx={x(hover)} cy={y(p[1])} r={4} className={`dot series-${s.slot}`} />
                  ) : null;
                })}
              </g>
            )}
          </svg>
        )}
        {hover !== null && !empty && (
          <div className="tooltip" style={{ left: Math.min(x(hover) + 12, width - 200) }} role="status">
            <strong>{new Date(hover * 1000).toLocaleTimeString()}</strong>
            {named.map((s) => {
              const p = s.points.find((q) => q[0] === hover);
              return (
                <div key={s.name} className="tooltip-row">
                  <span className={`swatch series-${s.slot}`} aria-hidden="true" />
                  <span>{s.name}</span>
                  <span className="num">{p && p[1] !== null && Number.isFinite(p[1]) ? format(p[1]) : '–'}</span>
                </div>
              );
            })}
          </div>
        )}
      </div>
      {named.length > 1 && (
        <ul className="legend">
          {named.map((s) => (
            <li key={s.name}>
              <span className={`swatch series-${s.slot}`} aria-hidden="true" />
              {s.name}
            </li>
          ))}
        </ul>
      )}
    </figure>
  );
}
