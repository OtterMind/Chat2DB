import { DatabaseTypeCode } from '@/constants';

export enum DataSourceStorageType {
  CLOUD = 'CLOUD',
  LOCAL = 'LOCAL',
}

// Connect to advanced configuration list information
export interface IConnectionExtendInfoItem {
  key: string;
  value: string;
}

// Connected environment information
export interface IConnectionEnv {
  id: number;
  name: string;
  shortName: string;
  color: string;
}

export interface IMysqlTlsConfig {
  tlsMode?: 'DISABLED' | 'REQUIRED' | 'VERIFY_CA' | 'VERIFY_IDENTITY';
  caPem?: string;
  clientCertPem?: string;
  clientPrivateKeyPem?: string;
  clientKeyPassword?: string;
  trustStoreType?: 'JKS' | 'PKCS12' | '';
  trustStoreBytes?: string;
  trustStorePassword?: string;
  keyStoreType?: 'JKS' | 'PKCS12' | '';
  keyStoreBytes?: string;
  keyStorePassword?: string;
}

export interface IConnectionDetails {
  spaceId: number;
  id: number;
  alias: string;
  environment: IConnectionEnv;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  type: DatabaseTypeCode;

  isAdmin: boolean;
  url: string;
  user: string;
  password: string;
  ConsoleOpenedStatus: 'y' | 'n';
  extendInfo: IConnectionExtendInfoItem[];
  environmentId: number;
  storageType: DataSourceStorageType;
  ssh: any;
  ssl?: IMysqlTlsConfig;
  driverConfig: {
    jdbcDriver: string;
    jdbcDriverClass: string;
  };
  [key: string]: any;
}

export interface IConnectionListItem {
  id: number;
  alias: string;
  environment: IConnectionEnv;
  environmentId?: number | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  type: DatabaseTypeCode;
  supportDatabase: boolean;
  supportSchema: boolean;
}

export interface IDataSourceIdentityColorUpdateRequest {
  id: number;
  identityColor: string | null;
}

export interface IDataSourceIdentityColorResponse {
  id: number;
  identityColor: string | null;
  environmentId: number | null;
  environment: IConnectionEnv | null;
}

export type ICreateConnectionDetails = Omit<IConnectionDetails, 'id'>;
