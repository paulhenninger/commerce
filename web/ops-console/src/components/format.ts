export function num(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '–';
  return value.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

export function rate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '–';
  return value < 10 ? value.toFixed(1) : Math.round(value).toLocaleString();
}

export function percent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '–';
  if (value === 0) return '0%';
  if (value < 0.001) return '<0.1%';
  return `${(value * 100).toFixed(value < 0.1 ? 1 : 0)}%`;
}

export function duration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '–';
  if (seconds < 1) return `${Math.round(seconds * 1000)} ms`;
  if (seconds < 120) return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
  if (seconds < 7200) return `${Math.round(seconds / 60)} min`;
  if (seconds < 172800) return `${Math.round(seconds / 3600)} h`;
  return `${Math.round(seconds / 86400)} d`;
}

export function bytes(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '–';
  const mib = value / 1048576;
  return mib >= 1024 ? `${(mib / 1024).toFixed(1)} GiB` : `${Math.round(mib)} MiB`;
}

export function when(iso: string | null | undefined): string {
  if (!iso) return '–';
  const t = new Date(iso);
  const ago = (Date.now() - t.getTime()) / 1000;
  if (ago < 60) return `${Math.max(0, Math.round(ago))} s ago`;
  if (ago < 3600) return `${Math.round(ago / 60)} min ago`;
  return t.toLocaleString();
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour12: false });
}
