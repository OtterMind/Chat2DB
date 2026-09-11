/**
 * Request parameters are attached to error notifications as a whole, and the user can copy those
 * details into a bug report. A request that carries a credential therefore has to have that value
 * removed before the parameters go anywhere else, which is what this helper does: it walks the
 * parameter object and replaces the value of every password-like field.
 */
const SENSITIVE_KEY_PATTERN = /password|passwd|pwd|passphrase|secret|token|credential|api[-_]?key/i;

export const REDACTED_VALUE = '***';

const isPlainObject = (value: unknown): value is Record<string, unknown> => {
  if (value === null || typeof value !== 'object') {
    return false;
  }
  // Files, blobs and form data already serialise to nothing useful, so they are left untouched.
  return !(value instanceof File) && !(value instanceof Blob) && !(value instanceof FormData);
};

/**
 * Returns a copy of the request parameters in which password-like values are replaced. The input is
 * never modified, so the value that was actually sent keeps working.
 */
export function redactSensitiveParams(params: unknown): unknown {
  if (Array.isArray(params)) {
    return params.map((item) => redactSensitiveParams(item));
  }
  if (!isPlainObject(params)) {
    return params;
  }
  const redacted: Record<string, unknown> = {};
  Object.keys(params).forEach((key) => {
    const value = params[key];
    if (!SENSITIVE_KEY_PATTERN.test(key)) {
      redacted[key] = redactSensitiveParams(value);
      return;
    }
    redacted[key] = value === undefined || value === null || value === '' ? value : REDACTED_VALUE;
  });
  return redacted;
}

export default redactSensitiveParams;
