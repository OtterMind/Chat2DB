export const SUBOTIZ_INVOICE_PORTAL_URL = 'https://checkout.subotiz.com/m/2821768/portal/login';

export interface InvoiceOrder {
  orderId?: string | null;
  subscriptionId?: string | null;
  status?: string | null;
}

export interface InvoiceDestination {
  provider: 'stripe' | 'subotiz';
  url: string;
}

function providerOf(order: InvoiceOrder): InvoiceDestination['provider'] | undefined {
  const subscriptionId = order.subscriptionId?.trim() || '';
  if (/^sub_/i.test(subscriptionId)) {
    return 'stripe';
  }
  if (/^\d+$/.test(subscriptionId)) {
    return 'subotiz';
  }
  return undefined;
}

function isCurrent(order: InvoiceOrder) {
  return order.status === 'ACTIVE' || order.status === 'TRIAL_CREATE';
}

export function resolveInvoiceDestination(orders: InvoiceOrder[]): InvoiceDestination | undefined {
  const supported = orders
    .map((order) => ({ order, provider: providerOf(order) }))
    .filter((candidate): candidate is { order: InvoiceOrder; provider: InvoiceDestination['provider'] } =>
      Boolean(candidate.provider),
    );
  const selected = supported.find(({ order }) => isCurrent(order)) || supported[0];
  if (!selected) {
    return undefined;
  }
  if (selected.provider === 'subotiz') {
    return { provider: 'subotiz', url: SUBOTIZ_INVOICE_PORTAL_URL };
  }

  const orderId = selected.order.orderId?.trim();
  const query = orderId
    ? `orderId=${encodeURIComponent(orderId)}`
    : `subId=${encodeURIComponent(selected.order.subscriptionId?.trim() || '')}`;
  return { provider: 'stripe', url: `/api/subscription/invoice/open?${query}` };
}
