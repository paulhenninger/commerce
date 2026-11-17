// The shop's API: catalog (public), orders (signed in), back office (administrators).

import { api } from './client';

export interface Product {
  sku: string;
  name: string;
  description: string;
  category: string;
  priceCents: number;
  currency: string;
  imageKey: string | null;
  active: boolean;
  available: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Line {
  sku: string;
  name: string;
  quantity: number;
  unitPriceCents: number;
}

export interface Address {
  name: string;
  line1: string;
  city: string;
  postalCode: string;
  country: string;
}

export interface HistoryEntry {
  status: string;
  detail: string;
  at: string;
}

export interface Order {
  id: string;
  status: OrderStatus;
  totalCents: number;
  currency: string;
  shippingAddress: Address;
  cancelReason: string | null;
  trackingNumber: string | null;
  returnTrackingNumber: string | null;
  createdAt: string;
  updatedAt: string;
  deliveredAt: string | null;
  lines: Line[];
  history: HistoryEntry[];
}

export type OrderStatus =
  | 'PENDING'
  | 'AWAITING_PAYMENT'
  | 'CONFIRMED'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'RETURN_REQUESTED'
  | 'RETURNED'
  | 'REFUNDED'
  | 'CANCELLED';

export interface OrderSummary {
  id: string;
  status: OrderStatus;
  totalCents: number;
  currency: string;
  items: number;
  createdAt: string;
}

export interface StockLevel {
  sku: string;
  onHand: number;
  reserved: number;
  available: number;
  updatedAt: string;
}

export interface ProductInput {
  name: string;
  description: string;
  category: string;
  priceCents: number;
  currency: string;
  imageKey: string | null;
  active: boolean;
}

export interface PlaceOrder {
  lines: { sku: string; quantity: number }[];
  shippingAddress: Address;
  paymentMethod: string;
  expectedTotalCents: number;
}

const q = (params: Record<string, string | number | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') s.set(k, String(v));
  const text = s.toString();
  return text ? `?${text}` : '';
};

export const shop = {
  products: (params: { category?: string; q?: string; page?: number; size?: number }) =>
    api<Page<Product>>(`/catalog/products${q(params)}`),
  product: (sku: string) => api<Product>(`/catalog/products/${encodeURIComponent(sku)}`),
  categories: () => api<string[]>('/catalog/categories'),
  placeOrder: (order: PlaceOrder, idempotencyKey: string) =>
    api<{ id: string; status: OrderStatus }>('/orders', {
      method: 'POST',
      body: JSON.stringify(order),
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  orders: (page: number) => api<Page<OrderSummary>>(`/orders${q({ page, size: 20 })}`),
  order: (id: string) => api<Order>(`/orders/${encodeURIComponent(id)}`),
  cancel: (id: string) => api<Order>(`/orders/${encodeURIComponent(id)}/cancel`, { method: 'POST' }),
  requestReturn: (id: string, reason: string) =>
    api<Order>(`/orders/${encodeURIComponent(id)}/return`, { method: 'POST', body: JSON.stringify({ reason }) }),
};

export const backoffice = {
  products: (search: string, page: number) =>
    api<Page<Product>>(`/backoffice/catalog/products${q({ q: search, page, size: 50 })}`),
  saveProduct: (sku: string, input: ProductInput) =>
    api<Product>(`/backoffice/catalog/products/${encodeURIComponent(sku)}`, { method: 'PUT', body: JSON.stringify(input) }),
  stock: (page: number) => api<Page<StockLevel>>(`/backoffice/inventory/stock${q({ page, size: 50 })}`),
  setStock: (sku: string, onHand: number) =>
    api<StockLevel>(`/backoffice/inventory/stock/${encodeURIComponent(sku)}`, {
      method: 'PUT',
      body: JSON.stringify({ onHand }),
    }),
  orders: (search: string, status: string, page: number) =>
    api<Page<OrderSummary>>(`/backoffice/orders${q({ q: search, status, page, size: 50 })}`),
  order: (id: string) => api<Order>(`/backoffice/orders/${encodeURIComponent(id)}`),
};

export function money(cents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(cents / 100);
}

/** What a customer reads for each status. */
export const STATUS_WORDS: Record<OrderStatus, string> = {
  PENDING: 'Checking stock',
  AWAITING_PAYMENT: 'Taking payment',
  CONFIRMED: 'Confirmed',
  SHIPPED: 'On its way',
  DELIVERED: 'Delivered',
  RETURN_REQUESTED: 'Return requested',
  RETURNED: 'Return received',
  REFUNDED: 'Refunded',
  CANCELLED: 'Cancelled',
};

export const CANCEL_REASONS: Record<string, string> = {
  CUSTOMER: 'You cancelled it.',
  OUT_OF_STOCK: 'We did not have enough of an item in stock.',
  PAYMENT_DECLINED: 'The payment was declined.',
  PAYMENT_UNAVAILABLE: 'We could not reach the payment provider. Nothing was charged.',
  TIMEOUT: 'It could not be completed in time. Nothing was charged.',
  EXPIRED: 'It could not be completed in time. Nothing was charged.',
};
