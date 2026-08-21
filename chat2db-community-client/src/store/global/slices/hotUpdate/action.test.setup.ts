Object.assign(globalThis as Record<string, unknown>, {
  __APP_NAME__: 'chat2db-community',
  __APP_CAPITAL_NAME__: 'Chat2DB Community',
  __APP_DISPLAY_NAME__: 'Chat2DB Community',
  __APP_PROTOCOL_SCHEME__: 'chat2db-community',
  __APP_VERSION__: '0.0.0-test',
  __RUNTIME_ENV__: 'community',
  __ENV__: 'production',
  window: globalThis,
  location: { search: '' },
  javaQuery: () => undefined,
});

Object.defineProperty(globalThis, 'navigator', {
  value: { userAgent: 'node', language: 'en-US', app_language: 'en-US', os_type: 'Windows' },
  configurable: true,
});
