import { resolveInvoiceDestination, SUBOTIZ_INVOICE_PORTAL_URL } from './invoice';

function assertEqual(actual: unknown, expected: unknown, message: string) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

const stripe = resolveInvoiceDestination([{ orderId: 'order/7', subscriptionId: 'sub_123', status: 'ACTIVE' }]);
assertEqual(stripe?.provider, 'stripe', 'Stripe subscription provider');
assertEqual(
  stripe?.url,
  '/api/subscription/invoice/open?orderId=order%2F7',
  'Stripe invoice uses the authenticated Enterprise route',
);

const subotiz = resolveInvoiceDestination([{ orderId: 'order-8', subscriptionId: '742913', status: 'ACTIVE' }]);
assertEqual(subotiz?.provider, 'subotiz', 'Subotiz subscription provider');
assertEqual(subotiz?.url, SUBOTIZ_INVOICE_PORTAL_URL, 'Subotiz invoice uses the customer portal');

const trialStripe = resolveInvoiceDestination([
  { orderId: 'expired-order', subscriptionId: '17', status: 'EXPIRED' },
  { orderId: ' trial order? ', subscriptionId: ' SUB_TRIAL ', status: 'TRIAL_CREATE' },
]);
assertEqual(trialStripe?.provider, 'stripe', 'Trial subscription is treated as current');
assertEqual(
  trialStripe?.url,
  '/api/subscription/invoice/open?orderId=trial%20order%3F',
  'Stripe order id is trimmed and encoded',
);

const activeBeforeExpired = resolveInvoiceDestination([
  { orderId: 'old-stripe', subscriptionId: 'sub_old', status: 'EXPIRED' },
  { orderId: 'current-subotiz', subscriptionId: '42', status: 'ACTIVE' },
]);
assertEqual(activeBeforeExpired?.provider, 'subotiz', 'Active subscription is preferred over expired history');

const stripeWithoutOrderId = resolveInvoiceDestination([{ subscriptionId: 'sub_fallback', status: 'ACTIVE' }]);
assertEqual(
  stripeWithoutOrderId?.url,
  '/api/subscription/invoice/open?subId=sub_fallback',
  'Stripe subscription id is used when an order id is unavailable',
);

assertEqual(
  resolveInvoiceDestination([{ orderId: 'license', status: 'ACTIVE' }]),
  undefined,
  'License and unknown records do not produce an invoice destination',
);
assertEqual(resolveInvoiceDestination([]), undefined, 'Empty order list does not produce an invoice destination');
assertEqual(
  resolveInvoiceDestination([{ subscriptionId: '   ', status: 'ACTIVE' }]),
  undefined,
  'Blank subscription id does not produce an invoice destination',
);

// eslint-disable-next-line no-console
console.log('invoice.test.ts: all invoice routing cases passed');
