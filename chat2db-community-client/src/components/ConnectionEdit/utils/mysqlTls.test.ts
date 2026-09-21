import assert from 'node:assert/strict';
import {
  base64FromDataUrl,
  collectMysqlTlsPayload,
  expandMysqlTlsConfig,
  mysqlTlsFileTypes,
  validateBrowserTlsFile,
} from './mysqlTls';

const expanded = expandMysqlTlsConfig({
  ssl: {
    tlsMode: 'VERIFY_IDENTITY',
    caPem: 'ca',
    clientCertPem: 'cert',
    clientPrivateKeyPem: 'key',
    clientKeyPassword: 'key-password',
    trustStoreType: 'JKS',
    trustStoreBytes: 'trust-base64',
    trustStorePassword: 'trust-password',
    keyStoreType: 'PKCS12',
    keyStoreBytes: 'base64',
    keyStorePassword: 'store-password',
  },
});

assert.equal(expanded.sslTlsMode, 'VERIFY_IDENTITY');
assert.equal(expanded.sslCaPem, 'ca');
assert.equal(expanded.sslClientPrivateKeyPem, 'key');
assert.equal(expanded.sslTrustStoreType, 'JKS');
assert.equal(expanded.sslTrustStoreBytes, 'trust-base64');
assert.equal(expanded.sslKeyStorePassword, 'store-password');
assert.equal(expanded.sslClearKeyStoreBytes, false);

const enabledFormData: Record<string, any> = {
  alias: 'mysql',
  sslTlsMode: 'VERIFY_CA',
  sslCaPem: '',
  sslClientCertPem: '',
  sslClientPrivateKeyPem: '',
  sslClientKeyPassword: '',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: 'trust-base64',
  sslTrustStorePassword: 'trust-password',
  sslKeyStoreType: 'JKS',
  sslKeyStoreBytes: 'base64',
  sslKeyStorePassword: 'store-password',
};
const enabledPayload = collectMysqlTlsPayload(enabledFormData);
assert.deepEqual(enabledPayload, {
  tlsMode: 'VERIFY_CA',
  caPem: '',
  clientCertPem: '',
  clientPrivateKeyPem: '',
  clientKeyPassword: '',
  trustStoreType: 'JKS',
  trustStoreBytes: 'trust-base64',
  trustStorePassword: 'trust-password',
  keyStoreType: 'JKS',
  keyStoreBytes: 'base64',
  keyStorePassword: 'store-password',
});
assert.equal(enabledFormData.alias, 'mysql');
assert.equal('sslTlsMode' in enabledFormData, false);

const disabledFormData: Record<string, any> = {
  sslTlsMode: 'DISABLED',
  sslCaPem: 'old-ca',
  sslClientCertPem: 'old-cert',
  sslClientPrivateKeyPem: 'old-key',
  sslClientKeyPassword: 'old-key-password',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: 'old-trust-store',
  sslTrustStorePassword: 'old-trust-store-password',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: 'old-store',
  sslKeyStorePassword: 'old-store-password',
};
assert.deepEqual(collectMysqlTlsPayload(disabledFormData), {
  tlsMode: 'DISABLED',
  caPem: '',
  clientCertPem: '',
  clientPrivateKeyPem: '',
  clientKeyPassword: '',
  trustStoreType: '',
  trustStoreBytes: '',
  trustStorePassword: '',
  keyStoreType: '',
  keyStoreBytes: '',
  keyStorePassword: '',
});

const redactedEditFormData: Record<string, any> = {
  alias: 'mysql-renamed',
  sslTlsMode: 'VERIFY_CA',
  sslCaPem: '',
  sslClientCertPem: '',
  sslClientPrivateKeyPem: '',
  sslClientKeyPassword: '',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: '',
  sslTrustStorePassword: '',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: '',
  sslKeyStorePassword: '',
  sslClearClientPrivateKeyPem: false,
  sslClearClientKeyPassword: false,
  sslClearTrustStoreBytes: false,
  sslClearTrustStorePassword: false,
  sslClearKeyStoreBytes: false,
  sslClearKeyStorePassword: false,
};
assert.deepEqual(
  collectMysqlTlsPayload(redactedEditFormData, {
    tlsMode: 'VERIFY_CA',
    trustStoreType: 'JKS',
    keyStoreType: 'PKCS12',
  }),
  {
    tlsMode: 'VERIFY_CA',
    caPem: '',
    clientCertPem: '',
    trustStoreType: 'JKS',
    keyStoreType: 'PKCS12',
  },
);
assert.equal(redactedEditFormData.alias, 'mysql-renamed');
assert.equal('sslClearKeyStoreBytes' in redactedEditFormData, false);

const explicitClearFormData: Record<string, any> = {
  sslTlsMode: 'VERIFY_CA',
  sslCaPem: 'ca',
  sslClientCertPem: 'cert',
  sslClientPrivateKeyPem: '',
  sslClientKeyPassword: '',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: '',
  sslTrustStorePassword: '',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: '',
  sslKeyStorePassword: '',
  sslClearTrustStoreBytes: true,
  sslClearKeyStoreBytes: true,
};
assert.deepEqual(
  collectMysqlTlsPayload(explicitClearFormData, {
    trustStoreType: 'JKS',
    keyStoreType: 'PKCS12',
  }),
  {
    tlsMode: 'VERIFY_CA',
    caPem: 'ca',
    clientCertPem: 'cert',
    trustStoreType: '',
    trustStoreBytes: '',
    trustStorePassword: '',
    keyStoreType: '',
    keyStoreBytes: '',
    keyStorePassword: '',
  },
);

assert.equal(base64FromDataUrl('data:application/octet-stream;base64,AAECAw=='), 'AAECAw==');
assert.deepEqual(mysqlTlsFileTypes('text'), ['pem']);
assert.deepEqual(mysqlTlsFileTypes('base64'), ['jks', 'p12', 'pfx', 'pkcs12']);
assert.doesNotThrow(() => validateBrowserTlsFile({ name: 'ca.pem', size: 1024 }, 'text'));
assert.doesNotThrow(() => validateBrowserTlsFile({ name: 'client.P12', size: 1024 }, 'base64'));
assert.throws(() => validateBrowserTlsFile({ name: 'ca.txt', size: 1024 }, 'text'), /Invalid TLS file/);
assert.throws(
  () => validateBrowserTlsFile({ name: 'client.p12', size: 10 * 1024 * 1024 + 1 }, 'base64'),
  /Invalid TLS file/,
);

const pemReplacementFormData: Record<string, any> = {
  sslTlsMode: 'VERIFY_IDENTITY',
  sslCaPem: 'new-ca-pem',
  sslClientCertPem: 'new-client-cert-pem',
  sslClientPrivateKeyPem: 'new-client-key-pem',
  sslClientKeyPassword: '',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: '',
  sslTrustStorePassword: '',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: '',
  sslKeyStorePassword: '',
};
assert.deepEqual(
  collectMysqlTlsPayload(pemReplacementFormData, {
    trustStoreType: 'JKS',
    keyStoreType: 'PKCS12',
  }),
  {
    tlsMode: 'VERIFY_IDENTITY',
    caPem: 'new-ca-pem',
    clientCertPem: 'new-client-cert-pem',
    clientPrivateKeyPem: 'new-client-key-pem',
    trustStoreType: '',
    trustStoreBytes: '',
    trustStorePassword: '',
    keyStoreType: '',
    keyStoreBytes: '',
    keyStorePassword: '',
  },
);

const storeReplacementFormData: Record<string, any> = {
  sslTlsMode: 'VERIFY_CA',
  sslCaPem: 'old-ca-pem',
  sslClientCertPem: 'old-client-cert-pem',
  sslClientPrivateKeyPem: '',
  sslClientKeyPassword: '',
  sslTrustStoreType: 'JKS',
  sslTrustStoreBytes: 'new-trust-store',
  sslTrustStorePassword: 'new-trust-password',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: 'new-client-store',
  sslKeyStorePassword: 'new-client-password',
};
assert.deepEqual(
  collectMysqlTlsPayload(storeReplacementFormData, {
    caPem: 'old-ca-pem',
    clientCertPem: 'old-client-cert-pem',
  }),
  {
    tlsMode: 'VERIFY_CA',
    caPem: '',
    clientCertPem: '',
    clientPrivateKeyPem: '',
    clientKeyPassword: '',
    trustStoreType: 'JKS',
    trustStoreBytes: 'new-trust-store',
    trustStorePassword: 'new-trust-password',
    keyStoreType: 'PKCS12',
    keyStoreBytes: 'new-client-store',
    keyStorePassword: 'new-client-password',
  },
);

console.log('mysql TLS connection form helpers passed');
