import { IMysqlTlsConfig } from '@/typings';

const MYSQL_TLS_FORM_FIELDS: Record<string, keyof IMysqlTlsConfig> = {
  sslTlsMode: 'tlsMode',
  sslCaPem: 'caPem',
  sslClientCertPem: 'clientCertPem',
  sslClientPrivateKeyPem: 'clientPrivateKeyPem',
  sslClientKeyPassword: 'clientKeyPassword',
  sslTrustStoreType: 'trustStoreType',
  sslTrustStoreBytes: 'trustStoreBytes',
  sslTrustStorePassword: 'trustStorePassword',
  sslKeyStoreType: 'keyStoreType',
  sslKeyStoreBytes: 'keyStoreBytes',
  sslKeyStorePassword: 'keyStorePassword',
};

type MysqlTlsContentField = Exclude<keyof IMysqlTlsConfig, 'tlsMode'>;
type MysqlTlsPreservableSecretField =
  | 'clientPrivateKeyPem'
  | 'clientKeyPassword'
  | 'trustStoreBytes'
  | 'trustStorePassword'
  | 'keyStoreBytes'
  | 'keyStorePassword';

const MAX_MYSQL_TLS_FILE_SIZE_BYTES = 10 * 1024 * 1024;

const MYSQL_TLS_CONTENT_FIELDS: MysqlTlsContentField[] = [
  'caPem',
  'clientCertPem',
  'clientPrivateKeyPem',
  'clientKeyPassword',
  'trustStoreType',
  'trustStoreBytes',
  'trustStorePassword',
  'keyStoreType',
  'keyStoreBytes',
  'keyStorePassword',
];

const MYSQL_TLS_PRESERVABLE_SECRET_FIELDS: MysqlTlsPreservableSecretField[] = [
  'clientPrivateKeyPem',
  'clientKeyPassword',
  'trustStoreBytes',
  'trustStorePassword',
  'keyStoreBytes',
  'keyStorePassword',
];

const MYSQL_TLS_CLEAR_FIELDS: Record<string, MysqlTlsPreservableSecretField> = {
  sslClearClientPrivateKeyPem: 'clientPrivateKeyPem',
  sslClearClientKeyPassword: 'clientKeyPassword',
  sslClearTrustStoreBytes: 'trustStoreBytes',
  sslClearTrustStorePassword: 'trustStorePassword',
  sslClearKeyStoreBytes: 'keyStoreBytes',
  sslClearKeyStorePassword: 'keyStorePassword',
};

export function expandMysqlTlsConfig(connectionData: { ssl?: IMysqlTlsConfig | null }) {
  const ssl = connectionData.ssl || {};
  return {
    sslTlsMode: ssl.tlsMode || 'DISABLED',
    sslCaPem: ssl.caPem || '',
    sslClientCertPem: ssl.clientCertPem || '',
    sslClientPrivateKeyPem: ssl.clientPrivateKeyPem || '',
    sslClientKeyPassword: ssl.clientKeyPassword || '',
    sslTrustStoreType: ssl.trustStoreType || '',
    sslTrustStoreBytes: ssl.trustStoreBytes || '',
    sslTrustStorePassword: ssl.trustStorePassword || '',
    sslKeyStoreType: ssl.keyStoreType || '',
    sslKeyStoreBytes: ssl.keyStoreBytes || '',
    sslKeyStorePassword: ssl.keyStorePassword || '',
    sslClearClientPrivateKeyPem: false,
    sslClearClientKeyPassword: false,
    sslClearTrustStoreBytes: false,
    sslClearTrustStorePassword: false,
    sslClearKeyStoreBytes: false,
    sslClearKeyStorePassword: false,
  };
}

export function collectMysqlTlsPayload(
  data: Record<string, any>,
  previousSsl?: IMysqlTlsConfig | null,
): IMysqlTlsConfig {
  const ssl: IMysqlTlsConfig = {};
  const forcedClearFields = new Set<keyof IMysqlTlsConfig>();
  const contentValues = ssl as Record<MysqlTlsContentField, string | undefined>;
  const preservableValues = ssl as Record<MysqlTlsPreservableSecretField, string | undefined>;
  Object.entries(MYSQL_TLS_FORM_FIELDS).forEach(([formField, sslField]) => {
    ssl[sslField] = data[formField];
    delete data[formField];
  });

  Object.entries(MYSQL_TLS_CLEAR_FIELDS).forEach(([formField, sslField]) => {
    if (data[formField] === true) {
      preservableValues[sslField] = '';
      forcedClearFields.add(sslField);
    }
    delete data[formField];
  });

  ssl.tlsMode = ssl.tlsMode || 'DISABLED';
  if (ssl.tlsMode === 'DISABLED') {
    MYSQL_TLS_CONTENT_FIELDS.forEach((field) => {
      contentValues[field] = '';
    });
    return ssl;
  }

  MYSQL_TLS_PRESERVABLE_SECRET_FIELDS.forEach((field) => {
    if (preservableValues[field] === '' && previousSsl && previousSsl[field] == null && !forcedClearFields.has(field)) {
      delete ssl[field];
    }
  });

  normalizeMysqlTlsSources(ssl);

  return ssl;
}

function normalizeMysqlTlsSources(ssl: IMysqlTlsConfig) {
  if (ssl.trustStoreBytes?.trim()) {
    ssl.caPem = '';
  } else if (ssl.caPem?.trim()) {
    ssl.trustStoreType = '';
    ssl.trustStoreBytes = '';
    ssl.trustStorePassword = '';
  }

  if (ssl.keyStoreBytes?.trim()) {
    ssl.clientCertPem = '';
    ssl.clientPrivateKeyPem = '';
    ssl.clientKeyPassword = '';
  } else if (ssl.clientCertPem?.trim() || ssl.clientPrivateKeyPem?.trim()) {
    ssl.keyStoreType = '';
    ssl.keyStoreBytes = '';
    ssl.keyStorePassword = '';
  }
}

export function base64FromDataUrl(dataUrl: string): string {
  const marker = ';base64,';
  const markerIndex = dataUrl.indexOf(marker);
  return markerIndex >= 0 ? dataUrl.slice(markerIndex + marker.length) : dataUrl;
}

export function readBrowserTlsFile(file: File, mode: 'text' | 'base64'): Promise<string> {
  validateBrowserTlsFile(file, mode);
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      resolve(mode === 'base64' ? base64FromDataUrl(result) : result);
    };
    reader.onerror = () => reject(reader.error || new Error('Failed to read file'));
    if (mode === 'base64') {
      reader.readAsDataURL(file);
    } else {
      reader.readAsText(file);
    }
  });
}

export function validateBrowserTlsFile(file: Pick<File, 'name' | 'size'>, mode: 'text' | 'base64') {
  const delimiter = file.name.lastIndexOf('.');
  const extension = delimiter < 0 ? '' : file.name.slice(delimiter + 1).toLowerCase();
  if (!mysqlTlsFileTypes(mode).includes(extension) || file.size > MAX_MYSQL_TLS_FILE_SIZE_BYTES) {
    throw new Error('Invalid TLS file');
  }
}

export function mysqlTlsFileTypes(mode: 'text' | 'base64' = 'text'): string[] {
  if (mode === 'base64') {
    return ['jks', 'p12', 'pfx', 'pkcs12'];
  }
  return ['pem'];
}
