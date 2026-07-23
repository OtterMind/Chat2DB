import { DatabaseTypeCode } from '@/constants';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { LangType } from '@/constants/settings';
import { DataSourceStorageType } from '@/typings';
import { AuthenticationType, InputType } from './enum';
import { IConnectionConfig } from './types';

export const sshConfig: IConnectionConfig['ssh'] = {
  items: [
    {
      defaultValue: false,
      inputType: InputType.SELECT,
      labelName: {
        [LangType.EN_US]: 'USE SSH',
        [LangType.ZH_CN]: '使用SSH',
        [LangType.JA_JP]: 'SSHを使用する',
      },
      name: 'use',
      required: false,
      selects: [
        {
          label: 'false',
          value: false,
        },
        {
          label: 'true',
          value: true,
        },
      ],
      styles: {
        labelWidth: {
          [LangType.EN_US]: '110px',
          [LangType.ZH_CN]: '80px',
          [LangType.JA_JP]: '110px',
        },
      },
    },
    {
      defaultValue: '',
      inputType: InputType.INPUT,
      labelName: {
        [LangType.EN_US]: 'SSH Hostname',
        [LangType.ZH_CN]: 'SSH 主机',
        [LangType.JA_JP]: 'SSH ホスト名',
      },
      name: 'hostName',
      required: false,
      styles: {
        width: '70%',
        labelWidth: {
          [LangType.EN_US]: '110px',
          [LangType.ZH_CN]: '80px',
          [LangType.JA_JP]: '110px',
        },
      },
    },
    {
      defaultValue: '22',
      inputType: InputType.INPUT,
      labelName: {
        [LangType.EN_US]: 'SSH Port',
        [LangType.ZH_CN]: 'SSH 端口',
        [LangType.JA_JP]: 'SSH ポート',
      },
      name: 'port',
      required: false,
      styles: {
        width: '30%',
        labelWidth: {
          [LangType.EN_US]: '90px',
          [LangType.ZH_CN]: '90px',
          [LangType.JA_JP]: '110px',
        },
        labelAlign: 'right',
      },
    },
    {
      defaultValue: '',
      inputType: InputType.INPUT,
      labelName: {
        [LangType.EN_US]: 'SSH UserName',
        [LangType.ZH_CN]: 'SSH 用户名',
        [LangType.JA_JP]: 'SSH ユーザー名',
      },
      name: 'userName',
      required: false,
      styles: {
        width: '70%',
        labelWidth: {
          [LangType.EN_US]: '110px',
          [LangType.ZH_CN]: '80px',
          [LangType.JA_JP]: '110px',
        },
      },
    },
    {
      defaultValue: '',
      inputType: InputType.INPUT,
      labelName: {
        [LangType.EN_US]: 'LocalPort',
        [LangType.ZH_CN]: '本地端口',
        [LangType.JA_JP]: 'ローカルポート',
      },
      name: 'localPort',
      placeholder: {
        [LangType.EN_US]: 'Need not fill in',
        [LangType.ZH_CN]: '无需填写',
        [LangType.JA_JP]: '記入不要',
      },
      required: false,
      styles: {
        width: '30%',
        labelWidth: {
          [LangType.EN_US]: '90px',
          [LangType.ZH_CN]: '90px',
          [LangType.JA_JP]: '110px',
        },
        labelAlign: 'right',
      },
    },
    {
      defaultValue: 'password',
      inputType: InputType.SELECT,
      labelName: {
        [LangType.EN_US]: 'Authentication',
        [LangType.ZH_CN]: '身份验证',
        [LangType.JA_JP]: '認証',
      },
      name: 'authenticationType',
      required: true,
      styles: {
        width: '30%',
        labelWidth: {
          [LangType.EN_US]: '100px',
          [LangType.ZH_CN]: '80px',
          [LangType.JA_JP]: '110px',
        },
      },
      selects: [
        {
          items: [
            {
              defaultValue: '',
              inputType: InputType.PASSWORD,
              labelName: {
                [LangType.EN_US]: 'Password',
                [LangType.ZH_CN]: '密码',
                [LangType.JA_JP]: 'パスワード',
              },
              name: 'password',
              required: true,
              styles: {
                labelWidth: {
                  [LangType.EN_US]: '100px',
                  [LangType.ZH_CN]: '80px',
                  [LangType.JA_JP]: '110px',
                },
              },
            },
          ],
          label: 'password',
          value: 'password',
        },
        {
          items: [
            {
              defaultValue: '',
              inputType: InputType.INPUT,
              labelName: {
                [LangType.EN_US]: 'Private key file',
                [LangType.ZH_CN]: '密钥文件',
                [LangType.JA_JP]: '秘密鍵ファイル',
              },
              name: 'keyFile',
              required: true,
              styles: {
                labelWidth: {
                  [LangType.EN_US]: '100px',
                  [LangType.ZH_CN]: '80px',
                  [LangType.JA_JP]: '110px',
                },
              },
              placeholder: {
                [LangType.EN_US]: '/user/userName/.ssh/xxxx',
                [LangType.ZH_CN]: '/user/userName/.ssh/xxxx',
                [LangType.JA_JP]: '/user/userName/.ssh/xxxx',
              },
            },
            {
              defaultValue: '',
              inputType: InputType.PASSWORD,
              labelName: {
                [LangType.EN_US]: 'Passphrase',
                [LangType.ZH_CN]: '密码短语',
                [LangType.JA_JP]: 'パスフレーズ',
              },
              name: 'passphrase',
              required: true,
              styles: {
                labelWidth: {
                  [LangType.EN_US]: '100px',
                  [LangType.ZH_CN]: '80px',
                  [LangType.JA_JP]: '110px',
                },
              },
            },
          ],
          label: 'Private key',
          value: 'keyFile',
        },
      ],
    },
  ],
};

export const envItem = {
  defaultValue: 2,
  inputType: InputType.SELECT,
  labelName: {
    [LangType.EN_US]: 'Env',
    [LangType.ZH_CN]: '环境',
    [LangType.JA_JP]: '環境',
  },
  name: 'environmentId',
  required: true,
  selects: [
    {
      label: 'RELEASE',
      value: 1,
    },
    {
      label: 'TEST',
      value: 2,
    },
  ],
  styles: {
    width: '50%',
  },
};

export const storageItem = {
  defaultValue: DataSourceStorageType.CLOUD,
  inputType: InputType.SELECT,
  labelName: {
    [LangType.EN_US]: 'Storage',
    [LangType.ZH_CN]: '存储',
    [LangType.JA_JP]: 'ストレージ',
  },
  name: 'storageType',
  required: true,
  selects: [
    {
      label: 'LOCAL',
      value: DataSourceStorageType.LOCAL,
    },
    {
      label: 'CLOUD',
      value: DataSourceStorageType.CLOUD,
    },
  ],
  styles: {
    width: '50%',
    labelWidth: {
      [LangType.EN_US]: '100px',
      [LangType.ZH_CN]: '70px',
      [LangType.JA_JP]: '110px',
    },
    labelAlign: 'right',
  },
  hidden: runtimeEditionConfig.localPersistence,
};

export const portItem: any = {
  inputType: InputType.INPUT,
  labelName: {
    [LangType.EN_US]: 'Port',
    [LangType.ZH_CN]: '端口',
    [LangType.JA_JP]: 'ポート',
  },
  name: 'port',
  labelTextAlign: 'right',
  required: true,
  styles: {
    width: '30%',
    labelWidth: {
      [LangType.EN_US]: '70px',
      [LangType.ZH_CN]: '70px',
      [LangType.JA_JP]: '80px',
    },
    labelAlign: 'right',
  },
};

export const dataSourceFormConfigs: IConnectionConfig[] = [
  // MYSQL
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '3306',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:mysql://localhost:3306',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:mysql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:mysql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.MYSQL,
  },
  // POSTGRESQL
  {
    type: DatabaseTypeCode.POSTGRESQL,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '5432',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'postgres',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:postgresql://localhost:5432',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:postgresql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:postgresql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // ORACLE
  {
    type: DatabaseTypeCode.ORACLE,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '1521',
          ...portItem,
        },
        {
          defaultValue: 'sid',
          inputType: InputType.SELECT,
          labelName: {
            [LangType.EN_US]: 'Service type',
            [LangType.ZH_CN]: '连接类型',
            [LangType.JA_JP]: 'サービスタイプ',
          },
          name: 'serviceType',
          required: true,
          selects: [
            {
              label: 'SID',
              value: 'sid',
              items: [
                {
                  defaultValue: 'XE',
                  inputType: InputType.INPUT,
                  labelName: {
                    [LangType.EN_US]: 'SID',
                    [LangType.ZH_CN]: 'SID',
                    [LangType.JA_JP]: 'SID',
                  },
                  name: 'sid',
                  required: true,
                  styles: {
                    width: '70%',
                  },
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:oracle:(.*):@(.*):(\d+):(.*)/;
                data.baseInfo.template = 'jdbc:oracle:{driver}:@{host}:{port}:{sid}';
                return data;
              },
            },
            {
              label: 'Service',
              value: 'service',
              items: [
                {
                  defaultValue: 'XE',
                  inputType: InputType.INPUT,
                  labelName: {
                    [LangType.EN_US]: 'Service name',
                    [LangType.ZH_CN]: '服务名',
                    [LangType.JA_JP]: 'サービス名',
                  },
                  name: 'serviceName',
                  required: true,
                  styles: {
                    width: '70%',
                  },
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:oracle:(.*):@\/\/(.*):(\d+)\/(.*)/;
                data.baseInfo.template = 'jdbc:oracle:{driver}:@//{host}:{port}/{serviceName}';
                return data;
              },
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'thin',
          inputType: InputType.SELECT,
          labelName: {
            [LangType.EN_US]: 'Driver',
            [LangType.ZH_CN]: '驱动',
            [LangType.JA_JP]: 'ドライバー',
          },
          name: 'driver',
          required: true,
          labelTextAlign: 'right',
          selects: [
            {
              value: 'thin',
              label: 'thin',
            },
            {
              value: 'oci',
              label: 'oci',
            },
            {
              value: 'oci8',
              label: 'oci8',
            },
          ],
          styles: {
            width: '30%',
            labelWidth: {
              [LangType.EN_US]: '70px',
              [LangType.ZH_CN]: '70px',
              [LangType.JA_JP]: '70px',
            },
            labelAlign: 'right',
          },
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'jdbc:oracle:thin:@localhost:1521:XE',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:oracle:(.*):@(.*):(\d+):(.*)/,
      template: 'jdbc:oracle:{driver}:@{host}:{port}:{sid}',
    },
    ssh: sshConfig,
  },
  // H2
  {
    type: DatabaseTypeCode.H2,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'TCP',
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Service type',
            [LangType.ZH_CN]: '连接类型',
            [LangType.JA_JP]: 'サービスタイプ',
          },
          name: 'serviceType',
          required: true,
          selects: [
            {
              label: 'TCP',
              value: 'TCP',
              items: [
                {
                  defaultValue: 'localhost',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Host',
                    [LangType.ZH_CN]: '主机',
                    [LangType.JA_JP]: 'ホスト',
                  },
                  name: 'host',
                  required: true,
                  styles: {
                    width: '70%',
                  },
                },
                {
                  defaultValue: '9092',
                  ...portItem,
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:h2:tcp:\/\/(.*):(\d+)(\/([\w-]+))?/;
                data.baseInfo.template = 'jdbc:h2:tcp://{host}:{port}/{database}';
                return data;
              },
            },
            {
              label: 'LocalFile',
              value: 'LocalFile',
              items: [
                {
                  defaultValue: '',
                  inputType: InputType.INPUT,
                  labelName: {
                    [LangType.EN_US]: 'File',
                    [LangType.ZH_CN]: '文件',
                    [LangType.JA_JP]: 'ファイル',
                  },
                  name: 'file',
                  required: true,
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:h2:(.*)?/;
                data.baseInfo.template = 'jdbc:h2:{file}';
                return data;
              },
            },
          ],
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],

              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:h2:tcp://localhost:9092',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:h2:tcp:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:h2:tcp://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // SQLSERVER encrypt=true;trustServerCertificate=true;integratedSecurity=false;Trusted_Connection=yes
  {
    type: DatabaseTypeCode.SQLSERVER,
    extendInfo: [
      {
        key: 'encrypt',
        value: 'false',
      },
      {
        key: 'trustServerCertificate',
        value: 'true',
      },
      {
        key: 'integratedSecurity',
        value: 'false',
      },
      {
        key: 'Trusted_Connection',
        value: 'yes',
      },
    ],
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '1433',
          ...portItem,
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Instance',
            [LangType.ZH_CN]: 'Instance',
            [LangType.JA_JP]: 'Instance',
          },
          name: 'instance',
          required: false,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],

              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:sqlserver://localhost:1433',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:sqlserver:\/\/(.*):(\d+)(;database=([\w-]+))?/,
      template: 'jdbc:sqlserver://{host}:{port};database={database}',
    },
    ssh: sshConfig,
  },
  // SQLITE
  {
    type: DatabaseTypeCode.SQLITE,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'identifier.sqlite',
          inputType: InputType.FILE,
          labelName: {
            [LangType.EN_US]: 'File',
            [LangType.ZH_CN]: 'File',
            [LangType.JA_JP]: 'File',
          },
          name: 'file',
          required: true,
          fileTypes: ['sqlite', 'sqlite3', 'db', 'db3'],
        },
        {
          defaultValue: 'jdbc:sqlite:identifier.sqlite',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:sqlite:(.*)?/,
      template: 'jdbc:sqlite:{file}',
    },
    ssh: sshConfig,
  },
  // MARIADB
  {
    type: DatabaseTypeCode.MARIADB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '3306',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],

              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:mariadb://localhost:3306',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:mariadb:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:mariadb://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // CLICKHOUSE
  {
    type: DatabaseTypeCode.CLICKHOUSE,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '8123',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],

              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:clickhouse://localhost:8123',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:clickhouse:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:clickhouse://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // DM
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '5236',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:dm://localhost:5236',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:dm:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:dm://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.DM,
  },
  // OSCAR
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '2003',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'sysdba',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'osrdb',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: true,
        },
        {
          defaultValue: 'jdbc:oscar://localhost:2003/osrdb',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:oscar:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:oscar://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    type: DatabaseTypeCode.OSCAR,
  },
  //DB2
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '50000',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:db2://localhost:50000',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:db2:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:db2://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.DB2,
  },
  //presto
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '8080',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:presto://localhost:8080',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:presto:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:presto://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.PRESTO,
  },
  //oceanbase
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '2883',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:oceanbase://localhost:2883',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:oceanbase:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:oceanbase://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.OCEANBASE,
  },
  // oceanbase oracle
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '1521',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:oceanbase://localhost:1521',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:oceanbase:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:oceanbase://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.OCEANBASE_ORACLE,
  },
  //redis
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'standalone',
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Service type',
            [LangType.ZH_CN]: '连接类型',
            [LangType.JA_JP]: 'サービスタイプ',
          },
          name: 'serviceType',
          required: true,
          selects: [
            {
              label: 'standalone',
              value: 'standalone',
              items: [
                {
                  defaultValue: 'localhost',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Host',
                    [LangType.ZH_CN]: '主机',
                    [LangType.JA_JP]: 'ホスト',
                  },
                  name: 'host',
                  required: true,
                  styles: {
                    width: '70%',
                  },
                },
                {
                  defaultValue: '9092',
                  ...portItem,
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:redis:\/\/(.*):(\d+)(\/([\w-]+))?/;
                data.baseInfo.template = 'jdbc:redis://{host}:{port}/{database}';
                return data;
              },
            },
            {
              label: 'cluster',
              value: 'cluster',
              items: [
                {
                  defaultValue: 'localhost',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Host',
                    [LangType.ZH_CN]: '主机',
                    [LangType.JA_JP]: 'ホスト',
                  },
                  name: 'host',
                  required: true,
                  styles: {
                    width: '70%',
                  },
                },
                {
                  defaultValue: '9092',
                  ...portItem,
                },
              ],
              onChange: (data: IConnectionConfig) => {
                data.baseInfo.pattern = /jdbc:redis:cluster:\/\/(.*):(\d+)(\/([\w-]+))?/;
                data.baseInfo.template = 'jdbc:redis:cluster://{host}:{port}/{database}';
                return data;
              },
            },
          ],
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: AuthenticationType.NONE,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              items: [
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'Password',
              value: AuthenticationType.PASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:redis://localhost:6379',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:redis:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:redis://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.REDIS,
  },
  //hive
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '10000',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:hive2://localhost:10000',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:hive2:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:hive2://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.HIVE,
  },
  //KINGBASE
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '54321',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'jdbc:kingbase8://127.0.0.1:54321',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /jdbc:kingbase8:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:kingbase8://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.KINGBASE,
  },
  // MONGODB
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
          styles: {
            width: '100%',
          },
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '27017',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                  styles: {
                    width: '100%',
                  },
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
          styles: {
            width: '100%',
          },
        },
        {
          defaultValue: 'mongodb://localhost:27017',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
          styles: {
            width: '100%',
          },
        },
      ],
      pattern: /mongodb:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'mongodb://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.MONGODB,
  },
  //SNOWFLAKE
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '443',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:snowflake://localhost:443',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:snowflake:\/\/(.*):(\d+)(\/\?db=([\w-]+))?/,
      template: 'jdbc:snowflake://{host}:{port}/?db={database}',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.SNOWFLAKE,
  },
  // OPENGAUSS
  {
    type: DatabaseTypeCode.OPENGAUSS,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '5432',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'postgres',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:opengauss://localhost:5432',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:opengauss:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:opengauss://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  //sundb
  {
    type: DatabaseTypeCode.SUNDB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '22581',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'system',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'sundb',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:sundb://localhost:22581',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:sundb:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:sundb://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  //TiDB
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '4000',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:mysql://localhost:4000',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:mysql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:mysql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.TIDB,
  },
  // COCKROACHDB
  {
    type: DatabaseTypeCode.COCKROACHDB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '26257',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'postgres',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:postgresql://localhost:26257',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:postgresql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:postgresql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // Kylin
  {
    type: DatabaseTypeCode.KYLIN,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '7070',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:kylin://localhost:7070',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:kylin:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:kylin://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // xugudb
  {
    type: DatabaseTypeCode.XUGUDB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '5138',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:xugu://localhost:5138',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:xugu:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:xugu://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // duckdb
  {
    type: DatabaseTypeCode.DUCKDB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'identifier.db',
          inputType: InputType.FILE,
          labelName: {
            [LangType.EN_US]: 'File',
            [LangType.ZH_CN]: 'File',
            [LangType.JA_JP]: 'File',
          },
          name: 'file',
          required: true,
          fileTypes: ['db', 'duckdb'],
        },
        {
          defaultValue: 'jdbc:duckdb:identifier.db',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:duckdb:(.*)?/,
      template: 'jdbc:duckdb:{file}',
    },
    ssh: sshConfig,
  },
  //es
  {
    type: DatabaseTypeCode.ELASTICSEARCH,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '9200',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:es://http://localhost:9200',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:es:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:es://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  //TDengine
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '6041',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:TAOS-RS://localhost:6041/',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:TAOS-RS:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:TAOS-RS://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.TDENGINE,
  },
  //bigquery
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'https://www.googleapis.com/bigquery/v2',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '443',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: '',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Project ID',
                    [LangType.ZH_CN]: 'Project ID',
                    [LangType.JA_JP]: 'Project ID',
                  },

                  name: 'project',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Account email',
                    [LangType.ZH_CN]: 'Account email',
                    [LangType.JA_JP]: 'Account email',
                  },

                  name: 'email',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'Key file',
                    [LangType.ZH_CN]: 'Key file',
                    [LangType.JA_JP]: 'Key file',
                  },

                  name: 'keyfile',
                  required: true,
                },
              ],
              label: 'Google Service Account',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Datatset',
            [LangType.ZH_CN]: 'Datatset',
            [LangType.JA_JP]: 'Datatset',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443/',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:bigquery:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:bigquery://{host}:{port}/',
    },
    ssh: sshConfig,
    extendInfo: [],
    type: DatabaseTypeCode.BIGQUERY,
  },
  // REDSHIFT
  {
    type: DatabaseTypeCode.REDSHIFT,
    baseInfo: {
      items: [
        {
          defaultValue: 'server.redshift.amazonaws.com',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '5439',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:redshift://server.redshift.amazonaws.com:5439',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:redshift:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:redshift://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },
  // INFORMIX
  {
    type: DatabaseTypeCode.INFORMIX,
    baseInfo: {
      items: [
        {
          defaultValue: 'Informix',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '1533',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Server',
            [LangType.ZH_CN]: '服务器',
            [LangType.JA_JP]: 'サーバー',
          },
          name: 'serviceName',
          required: true,
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:informix-sqli://localhost:1533/',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:informix-sqli:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:informix-sqli://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'CLIENT_LOCALE',
        value: 'en_us.utf8',
      },
      {
        key: 'DB_LOCALE',
        value: 'en_us.utf8',
      },
    ],
  },
  // DORIS
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '9030',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:mysql://localhost:9030',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:mysql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:mysql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.DORIS,
  },
  //starrocks
  {
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '9030',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },

          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },

                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },

                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,
          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },

          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:mysql://localhost:9030',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },

          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:mysql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:mysql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'zeroDateTimeBehavior',
        value: 'convertToNull',
      },
    ],
    type: DatabaseTypeCode.STARROCKS,
  },
  // GaussDB
  {
    type: DatabaseTypeCode.GAUSSDB,
    baseInfo: {
      items: [
        {
          defaultValue: '@localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '8000',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: 'postgres',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:postgresql://localhost:8000',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:postgresql:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:postgresql://{host}:{port}/{database}',
    },
    ssh: sshConfig,
  },

  // GBase8s
  {
    type: DatabaseTypeCode.GBASE8S,
    baseInfo: {
      items: [
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Name',
            [LangType.ZH_CN]: '名称',
            [LangType.JA_JP]: '名前',
          },
          name: 'alias',
          required: true,
        },
        envItem,
        storageItem,
        {
          defaultValue: 'localhost',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Host',
            [LangType.ZH_CN]: '主机',
            [LangType.JA_JP]: 'ホスト',
          },
          name: 'host',
          required: true,
          styles: {
            width: '70%',
          },
        },
        {
          defaultValue: '19088',
          ...portItem,
        },
        {
          defaultValue: AuthenticationType.USERANDPASSWORD,
          inputType: InputType.SELECT,

          labelName: {
            [LangType.EN_US]: 'Authentication',
            [LangType.ZH_CN]: '身份验证',
            [LangType.JA_JP]: '認証',
          },
          name: 'authenticationType',
          required: true,
          selects: [
            {
              items: [
                {
                  defaultValue: 'root',
                  inputType: InputType.INPUT,

                  labelName: {
                    [LangType.EN_US]: 'User',
                    [LangType.ZH_CN]: '用户名',
                    [LangType.JA_JP]: 'ユーザー名',
                  },
                  name: 'user',
                  required: true,
                },
                {
                  defaultValue: '',
                  inputType: InputType.PASSWORD,

                  labelName: {
                    [LangType.EN_US]: 'Password',
                    [LangType.ZH_CN]: '密码',
                    [LangType.JA_JP]: 'パスワード',
                  },
                  name: 'password',
                  required: true,
                },
              ],
              label: 'User&Password',
              value: AuthenticationType.USERANDPASSWORD,
            },
            {
              label: 'NONE',
              value: AuthenticationType.NONE,
              items: [],
            },
          ],
          styles: {
            width: '50%',
          },
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Server',
            [LangType.ZH_CN]: '服务器',
            [LangType.JA_JP]: 'サーバー',
          },
          name: 'serviceName',
          required: true,
        },
        {
          defaultValue: '',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'Database',
            [LangType.ZH_CN]: '数据库',
            [LangType.JA_JP]: 'データベース',
          },
          name: 'database',
          required: false,
        },
        {
          defaultValue: 'jdbc:gbasedbt-sqli://localhost:19088/',
          inputType: InputType.INPUT,

          labelName: {
            [LangType.EN_US]: 'URL',
            [LangType.ZH_CN]: 'URL',
            [LangType.JA_JP]: 'URL',
          },
          name: 'url',
          required: true,
        },
      ],
      pattern: /jdbc:gbasedbt-sqli:\/\/(.*):(\d+)(\/([\w-]+))?/,
      template: 'jdbc:gbasedbt-sqli://{host}:{port}/{database}',
    },
    ssh: sshConfig,
    extendInfo: [
      {
        key: 'CLIENT_LOCALE',
        value: 'en_us.utf8',
      },
      {
        key: 'DB_LOCALE',
        value: 'en_us.utf8',
      },
    ],
  },
];
