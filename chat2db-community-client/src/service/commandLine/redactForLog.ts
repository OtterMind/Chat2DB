const SENSITIVE_LOG_KEY = /password|passphrase|api[_-]?key|secret|token|authorization/i;

export const redactForLog = (value: unknown): unknown => {
  // JDBC dialects encode credentials differently; omit the whole connection string from logs.
  if (typeof value === 'string' && /^jdbc:/i.test(value.trimStart())) {
    return '***';
  }
  if (Array.isArray(value)) {
    return value.map((item) => redactForLog(item));
  }
  if (value && typeof value === 'object') {
    const sensitiveEntry = 'key' in value && typeof value.key === 'string' && SENSITIVE_LOG_KEY.test(value.key);
    return Object.fromEntries(
      Object.entries(value).map(([key, itemValue]) => [
        key,
        SENSITIVE_LOG_KEY.test(key) || (key === 'value' && sensitiveEntry) ? '***' : redactForLog(itemValue),
      ]),
    );
  }
  return value;
};
