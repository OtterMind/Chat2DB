const SENSITIVE_LOG_KEY = /password|passphrase|apikey|secret|token|authorization/i;

export const redactForLog = (value: any): any => {
  if (Array.isArray(value)) {
    return value.map((item) => redactForLog(item));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, itemValue]) => [
        key,
        SENSITIVE_LOG_KEY.test(key) ? '***' : redactForLog(itemValue),
      ]),
    );
  }
  return value;
};
