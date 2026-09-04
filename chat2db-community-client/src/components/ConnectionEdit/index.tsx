import LoadingGracile from '@/components/Loading/LoadingGracile';
import DataSourceColorPicker from '@/components/DataSourceColorPicker';
import { ConnectionEnvType, DatabaseTypeCode } from '@/constants';
import { LangType } from '@/constants/settings';
import { i18n } from '@/i18n';
import jcefApi from '@/jcef';
import connectionService from '@/service/connection';
import { DataSourceStorageType, IConnectionDetails } from '@/typings';
import { deepClone } from '@/utils';
import { FolderOpenOutlined } from '@ant-design/icons';
import { Button, Checkbox, Collapse, Form, Input, Select, Table, Tooltip } from 'antd';
import classnames from 'classnames';
import { CircleHelp } from 'lucide-react';
import React, { ForwardedRef, Fragment, forwardRef, useEffect, useImperativeHandle, useMemo, useState } from 'react';
import Driver from './components/Driver';
import { dataSourceFormConfigs } from './config/dataSource';
import { InputType } from './config/enum';
import { IConnectionConfig, IFormItem, ILocalizedConnectionText, ISelect } from './config/types';
import { applyConnectionIdentityColorUpdate } from './identityColorUpdate';
import styles from './index.less';
import { formatJdbcHostForUrl, normalizeJdbcHostFromUrl, shouldSyncJdbcUrlForField } from './utils/jdbcUrl';
import {
  collectMysqlTlsPayload,
  expandMysqlTlsConfig,
  mysqlTlsFileTypes,
  readBrowserTlsFile,
} from './utils/mysqlTls';

// ----- store -----
import { clientRuntime } from '@client-runtime';
import { useGlobalStore } from '@/store/global';
import { useOrgStore } from '@/store/workspaceContext';
import clientExtension from '@client-extension';
import { staticMessage } from '@chat2db/ui';

const { Option } = Select;

type ITabsType = 'ssh' | 'baseInfo' | 'driver';

const OSCAR_JDBC_URL_PREFIX = 'jdbc:oscar://';
const OSCAR_DRIVER_CLASS = 'com.oscar.Driver';

const connectionFormTranslations: Partial<Record<LangType, Record<string, string>>> = {
  [LangType.ZH_CN]: {
    'User&Password': '用户名和密码',
    NONE: '无',
  },
  [LangType.JA_JP]: {
    'User&Password': 'ユーザー名とパスワード',
    NONE: 'なし',
  },
  [LangType.ES_ES]: {
    'USE SSH': 'Usar SSH',
    'SSH Hostname': 'Host SSH',
    'SSH Port': 'Puerto SSH',
    'SSH UserName': 'Usuario SSH',
    LocalPort: 'Puerto local',
    'Need not fill in': 'Opcional',
    Authentication: 'Autenticación',
    Password: 'Contraseña',
    password: 'Contraseña',
    'Private key file': 'Archivo de clave privada',
    'Private key': 'Clave privada',
    Passphrase: 'Frase de contraseña',
    Env: 'Entorno',
    Storage: 'Almacenamiento',
    Port: 'Puerto',
    Name: 'Nombre',
    Host: 'Host',
    User: 'Usuario',
    Database: 'Base de datos',
    'Service type': 'Tipo de servicio',
    'Service name': 'Nombre del servicio',
    Driver: 'Controlador',
    'Account email': 'Correo de la cuenta',
    'Project ID': 'ID del proyecto',
    File: 'Archivo',
    'Key file': 'Archivo de clave',
    Server: 'Servidor',
    Instance: 'Instancia',
    Datatset: 'Conjunto de datos',
    'Google Service Account': 'Cuenta de servicio de Google',
    'User&Password': 'Usuario y contraseña',
    NONE: 'Ninguno',
    LocalFile: 'Archivo local',
    Service: 'Servicio',
  },
  [LangType.KO_KR]: {
    'USE SSH': 'SSH 사용',
    'SSH Hostname': 'SSH 호스트',
    'SSH Port': 'SSH 포트',
    'SSH UserName': 'SSH 사용자 이름',
    LocalPort: '로컬 포트',
    'Need not fill in': '선택 사항',
    Authentication: '인증',
    Password: '비밀번호',
    password: '비밀번호',
    'Private key file': '개인 키 파일',
    'Private key': '개인 키',
    Passphrase: '암호 구문',
    Env: '환경',
    Storage: '저장소',
    Port: '포트',
    Name: '이름',
    Host: '호스트',
    User: '사용자',
    Database: '데이터베이스',
    'Service type': '서비스 유형',
    'Service name': '서비스 이름',
    Driver: '드라이버',
    'Account email': '계정 이메일',
    'Project ID': '프로젝트 ID',
    File: '파일',
    'Key file': '키 파일',
    Server: '서버',
    Instance: '인스턴스',
    Datatset: '데이터 세트',
    'Google Service Account': 'Google 서비스 계정',
    'User&Password': '사용자 및 비밀번호',
    NONE: '없음',
    LocalFile: '로컬 파일',
    Service: '서비스',
  },
};

function localizeConnectionFormText(text: string | undefined, language: LangType) {
  if (!text) {
    return text;
  }
  return connectionFormTranslations[language]?.[text] || text;
}

function resolveLocalizedConnectionText(text: ILocalizedConnectionText | undefined, language: LangType) {
  const directTranslation = text?.[language];
  if (directTranslation) {
    return directTranslation;
  }
  return localizeConnectionFormText(text?.[LangType.EN_US], language);
}

function hasDataSourceFormConfig(type?: string) {
  return !!type && dataSourceFormConfigs.some((item) => item.type === type);
}

function resolveConnectionType(connectionData?: Partial<IConnectionDetails> | null) {
  const type = connectionData?.type;
  if (hasDataSourceFormConfig(type)) {
    return type;
  }

  const jdbcUrl = connectionData?.url?.toLowerCase();
  const jdbcDriverClass = connectionData?.driverConfig?.jdbcDriverClass;
  if (jdbcUrl?.startsWith(OSCAR_JDBC_URL_PREFIX) || jdbcDriverClass === OSCAR_DRIVER_CLASS) {
    return DatabaseTypeCode.OSCAR;
  }

  return type;
}

function normalizeConnectionData(connectionData?: IConnectionDetails | null) {
  if (!connectionData) {
    return {} as IConnectionDetails;
  }
  const resolvedType = resolveConnectionType(connectionData);
  return {
    ...connectionData,
    ...(resolvedType && resolvedType !== connectionData.type ? { type: resolvedType } : {}),
    watermarkEnabled: connectionData.watermarkEnabled === true,
    watermarkContent: connectionData.watermarkContent || '',
    ...expandMysqlTlsConfig(connectionData),
  };
}

function mergeSavedConnectionData(current: IConnectionDetails, saved: any) {
  if (!saved || typeof saved !== 'object') {
    return {
      ...current,
      id: saved,
    };
  }

  const next = {
    ...saved,
  };
  if (next.password == null) {
    delete next.password;
  }

  return {
    ...current,
    ...next,
    id: saved.id,
  };
}

function resolveDataSourceFormConfig(type?: string): IConnectionConfig {
  const clonedConfigs = deepClone(dataSourceFormConfigs);
  const config =
    clonedConfigs.find((item: IConnectionConfig) => item.type === type) ||
    clonedConfigs.find((item: IConnectionConfig) => item.type === DatabaseTypeCode.MYSQL) ||
    clonedConfigs[0];
  const identityItems: IFormItem[] = [
    {
      defaultValue: null,
      inputType: InputType.COLOR,
      labelKey: 'workspace.identityColor.label',
      helpKey: 'workspace.identityColor.help',
      name: 'identityColor',
      required: false,
    },
    {
      defaultValue: false,
      inputType: InputType.CHECKBOX,
      labelKey: 'workspace.watermark.label',
      helpKey: 'workspace.watermark.help',
      name: 'watermarkEnabled',
      required: false,
    },
    {
      defaultValue: '',
      visibleWhen: {
        name: 'watermarkEnabled',
        value: true,
      },
      inputType: InputType.INPUT,
      labelKey: 'workspace.watermark.contentLabel',
      maxLength: 64,
      name: 'watermarkContent',
      placeholderKey: 'workspace.watermark.contentPlaceholder',
      required: false,
    },
  ];
  const identityItemNames = new Set(identityItems.map((item) => item.name));
  const configuredIdentityItems = new Map(
    config.baseInfo.items
      .filter((item) => identityItemNames.has(item.name))
      .map((item) => [item.name, item]),
  );
  const baseInfoItems = config.baseInfo.items.filter((item) => !identityItemNames.has(item.name));
  const environmentIndex = baseInfoItems.findIndex((item) => item.name === 'environmentId');
  if (environmentIndex >= 0) {
    const groupedItems = [
      { ...baseInfoItems[environmentIndex], layoutGroup: 'dataSourceIdentity' as const },
      ...identityItems.map((item) => ({
        ...(configuredIdentityItems.get(item.name) || item),
        layoutGroup: 'dataSourceIdentity' as const,
      })),
    ];
    config.baseInfo.items = [
      ...baseInfoItems.slice(0, environmentIndex),
      ...groupedItems,
      ...baseInfoItems.slice(environmentIndex + 1),
    ];
  }
  return config;
}

function resolveSelectedFilePath(data: any): string | undefined {
  if (!data) {
    return undefined;
  }
  if (data.data) {
    return resolveSelectedFilePath(data.data);
  }
  if (Array.isArray(data)) {
    for (const item of data) {
      const filePath = resolveSelectedFilePath(item);
      if (filePath) {
        return filePath;
      }
    }
    return undefined;
  }
  if (typeof data === 'string') {
    return data;
  }
  return data.filePath || data.path || data.file?.path || data.fileName;
}

interface IFilePathInputProps {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  fileTypes?: string[];
}

const FILE_SELECT_LOG_PREFIX = 'select local file';

function FilePathInput(props: IFilePathInputProps) {
  const { value, onChange, placeholder, disabled, fileTypes = [] } = props;
  const webFileInputRef = React.useRef<HTMLInputElement>(null);
  const [selecting, setSelecting] = useState(false);

  function triggerChange(filePath: string) {
    onChange?.(filePath);
  }

  async function selectLocalFile() {
    if (disabled || selecting) {
      return;
    }

    if (typeof window.javaQuery === 'function') {
      setSelecting(true);
      try {
        const data = await jcefApi.selectFile({ fileTypeList: fileTypes });
        const filePath = resolveSelectedFilePath(data);
        if (filePath) {
          triggerChange(filePath);
        }
        return;
      } catch (error) {
        console.error(`${FILE_SELECT_LOG_PREFIX} by jcef error`, error);
        staticMessage.error(i18n('common.text.selectFileFailed'));
        return;
      } finally {
        setSelecting(false);
      }
    }

    webFileInputRef.current?.click();
  }

  function onWebFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] as (File & { path?: string; webkitRelativePath?: string }) | undefined;
    const filePath = file?.path || file?.webkitRelativePath || file?.name;
    if (filePath) {
      triggerChange(filePath);
    }
    event.target.value = '';
  }

  return (
    <div className={styles.filePathInputBox}>
      <Input
        className={styles.filePathInput}
        value={value}
        onChange={(event) => triggerChange(event.target.value)}
        placeholder={placeholder}
        disabled={disabled}
      />
      <Button
        className={styles.filePathSelectButton}
        disabled={disabled || selecting}
        htmlType="button"
        icon={<FolderOpenOutlined />}
        loading={selecting}
        onMouseDown={(event) => event.preventDefault()}
        onClick={selectLocalFile}
      >
        {i18n('common.text.selectFile')}
      </Button>
      <input
        ref={webFileInputRef}
        accept={fileTypes.map((fileType) => `.${fileType}`).join(',')}
        className={styles.hiddenFileInput}
        tabIndex={-1}
        type="file"
        hidden
        onChange={onWebFileChange}
      />
    </div>
  );
}

interface IFileContentTextAreaProps {
  value?: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  fileTypes?: string[];
  mode?: 'text' | 'base64';
  rows?: number;
  maxLength?: number;
}

function FileContentTextArea(props: IFileContentTextAreaProps) {
  const {
    value,
    onChange,
    placeholder,
    disabled,
    mode = 'text',
    rows = 3,
    maxLength,
  } = props;
  const webFileInputRef = React.useRef<HTMLInputElement>(null);
  const [selecting, setSelecting] = useState(false);
  const fileTypes = props.fileTypes?.length ? props.fileTypes : mysqlTlsFileTypes(mode);

  function triggerChange(content: string) {
    onChange?.(content);
  }

  async function selectLocalFile() {
    if (disabled || selecting) {
      return;
    }

    if (typeof window.javaQuery === 'function') {
      setSelecting(true);
      try {
        const data = await jcefApi.selectTlsFileContent({ fileTypeList: fileTypes, mode });
        if (data?.content) {
          triggerChange(data.content);
        }
        return;
      } catch (error) {
        console.error('select TLS file content by jcef error', error);
        staticMessage.error(i18n('common.text.selectFileFailed'));
        return;
      } finally {
        setSelecting(false);
      }
    }

    webFileInputRef.current?.click();
  }

  async function onWebFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    setSelecting(true);
    try {
      triggerChange(await readBrowserTlsFile(file, mode));
    } catch (error) {
      console.error('read TLS file content by browser error', error);
      staticMessage.error(i18n('common.text.selectFileFailed'));
    } finally {
      setSelecting(false);
      event.target.value = '';
    }
  }

  return (
    <div className={styles.fileContentInputBox}>
      <Input.TextArea
        className={styles.fileContentTextArea}
        disabled={disabled}
        maxLength={maxLength}
        placeholder={placeholder}
        rows={rows}
        value={value}
        onChange={(event) => triggerChange(event.target.value)}
      />
      <Button
        className={styles.fileContentSelectButton}
        disabled={disabled || selecting}
        htmlType="button"
        icon={<FolderOpenOutlined />}
        loading={selecting}
        onMouseDown={(event) => event.preventDefault()}
        onClick={selectLocalFile}
      >
        {i18n('common.text.selectFile')}
      </Button>
      <input
        ref={webFileInputRef}
        accept={fileTypes.map((fileType) => `.${fileType}`).join(',')}
        className={styles.hiddenFileInput}
        tabIndex={-1}
        type="file"
        hidden
        onChange={onWebFileChange}
      />
    </div>
  );
}

export enum submitType {
  UPDATE = 'update',
  SAVE = 'save',
  TEST = 'test',
}

function getConnectionErrorMessage(error: any) {
  if (!error) {
    return i18n('connection.message.testConnectResult', i18n('common.text.failure'));
  }

  if (typeof error === 'string') {
    return error;
  }

  return (
    error.errorMessage || error.message || i18n('connection.message.testConnectResult', i18n('common.text.failure'))
  );
}

interface IProps {
  closeCreateConnection: () => void;
  connectionData: IConnectionDetails;
  submit?: (data: IConnectionDetails, type: submitType) => Promise<any>;
}

export interface ICreateConnectionFunction {
  getData: () => IConnectionDetails;
}

const ConnectionEdit = forwardRef((props: IProps, ref: ForwardedRef<ICreateConnectionFunction>) => {
  const { closeCreateConnection, connectionData, submit } = props;
  const [baseInfoForm] = Form.useForm();
  const [sshForm] = Form.useForm();
  const [driveData, setDriveData] = useState<any>({});
  const [backfillData, setBackfillData] = useState<IConnectionDetails>(() => normalizeConnectionData(connectionData));
  const [loadings, setLoading] = useState({
    confirmButton: false,
    testButton: false,
    sshTestLoading: false,
  });
  const { curOrg } = useOrgStore((s) => ({ curOrg: s.curOrg }));

  const dataSourceFormConfigPropsMemo = useMemo<IConnectionConfig>(() => {
    const data = resolveDataSourceFormConfig(backfillData?.type);

    const items = data?.baseInfo?.items || [];
    const storagePolicy = clientExtension.connectionStoragePolicy?.(curOrg);
    if (storagePolicy) {
      const storage = items.find((t) => t.name === 'storageType');
      if (storage) {
        storage.defaultValue = storagePolicy.value as DataSourceStorageType;
        storage.disabled = storagePolicy.disabled;
      }
    }
    return data;
  }, [backfillData, curOrg]);

  const { curIsPersonalOrg } = useOrgStore((s) => ({
    curIsPersonalOrg: s.curIsPersonalOrg,
  }));

  useEffect(() => {
    setBackfillData(normalizeConnectionData(props.connectionData));
  }, [props.connectionData]);

  function driverFormChange(data: any) {
    setDriveData(data);
  }

  const getItems = () => [
    {
      forceRender: true,
      key: 'driver',
      label: i18n('connection.title.driver'),
      children: (
        <Driver backfillData={backfillData} onChange={driverFormChange} disabled={backfillData.isAdmin === false} />
      ),
    },
    {
      key: 'ssh',
      forceRender: true,
      label: i18n('connection.label.sshConfiguration'),
      children: (
        <div className={styles.sshBox}>
          <RenderForm
            dataSourceFormConfigProps={dataSourceFormConfigPropsMemo}
            backfillData={backfillData!}
            form={sshForm}
            tab="ssh"
            disabled={backfillData.isAdmin === false}
          />
          <div className={styles.testSSHConnect}>
            {loadings.sshTestLoading && <LoadingGracile />}
            <div onClick={testSSH} className={styles.testSSHConnectText}>
              {i18n('connection.message.testSshConnection')}
            </div>
          </div>
        </div>
      ),
    },
    {
      forceRender: true,
      key: 'extendInfo',
      label: i18n('connection.label.advancedConfiguration'),
      children: (
        <div className={styles.extendInfoBox}>
          <RenderExtendTable backfillData={backfillData!} />
        </div>
      ),
    },
  ];

  useImperativeHandle(ref, () => ({
    getData,
  }));

  function getData() {
    const ssh = sshForm.getFieldsValue();
    const baseInfo = baseInfoForm.getFieldsValue();
    baseInfo.watermarkEnabled = baseInfo.watermarkEnabled === true;
    baseInfo.watermarkContent = baseInfo.watermarkContent?.trim() || '';
    if (baseInfo.host) {
      baseInfo.host = normalizeJdbcHostFromUrl(baseInfo.host);
    }
    const ssl = backfillData.type === DatabaseTypeCode.MYSQL
      ? collectMysqlTlsPayload(baseInfo, backfillData.ssl)
      : undefined;
    const extendInfo: any = [];
    extendTableData.map((t: any) => {
      if (t.label || t.value) {
        extendInfo.push({
          key: t.label,
          value: t.value,
        });
      }
    });

    const data = {
      ssh,
      driverConfig: driveData,
      ...baseInfo,
      extendInfo,
      connectionEnvType: ConnectionEnvType.DAILY,
      type: backfillData.type,
    };
    if (ssl) {
      data.ssl = ssl;
    }

    if (backfillData.id) {
      data.id = backfillData.id;
    }

    return data;
  }

  // Test, save, or update the connection.
  function saveConnection(type: submitType) {
    const p = getData();

    if (type !== submitType.SAVE) {
      p.id = backfillData.id;
    }

    if (clientRuntime.usesLocalPersistence) {
      p.storageType = DataSourceStorageType.LOCAL;
    } else if (!curIsPersonalOrg()) {
      p.storageType = DataSourceStorageType.CLOUD;
    }

    const loadingsButton = type === submitType.TEST ? 'testButton' : 'confirmButton';

    setLoading((state) => ({
      ...state,
      [loadingsButton]: true,
    }));

    if ((type === submitType.SAVE || type === submitType.UPDATE) && submit) {
      Promise.resolve(submit(p, type))
        .catch((error: any) => {
          staticMessage.error(getConnectionErrorMessage(error));
        })
        .finally(() => {
          setLoading((state) => ({
            ...state,
            [loadingsButton]: false,
          }));
        });
      return;
    }

    const api: any = connectionService[type](p);
    if (type === submitType.TEST) {
      api
        .then((res: any) => {
          const isSuccessful = res !== false;
          const message = i18n(
            'connection.message.testConnectResult',
            i18n(isSuccessful ? 'common.text.successful' : 'common.text.failure'),
          );

          if (isSuccessful) {
            staticMessage.success(message);
          } else {
            staticMessage.error(message);
          }
        })
        .catch((error: any) => {
          const message = getConnectionErrorMessage(error);
          staticMessage.error(message);
        })
        .finally(() => {
          setLoading((state) => ({
            ...state,
            [loadingsButton]: false,
          }));
        });
      return;
    }

    api
      .then((res: any) => {
        if (type !== submitType.UPDATE) {
          return res;
        }
        return applyConnectionIdentityColorUpdate(
          res,
          backfillData.identityColor,
          p.identityColor,
          connectionService.updateIdentityColor,
        );
      })
      .then((res: any) => {
        staticMessage.success(
          type === submitType.UPDATE
            ? i18n('common.message.modifySuccessfully')
            : i18n('common.message.addedSuccessfully'),
        );

        if (type === submitType.SAVE) {
          setBackfillData(mergeSavedConnectionData(backfillData, res));
        }
      })
      .finally(() => {
        setLoading((state) => ({
          ...state,
          [loadingsButton]: false,
        }));
      });
  }

  function onCancel() {
    closeCreateConnection();
  }

  function testSSH() {
    const p = sshForm.getFieldsValue();
    setLoading({
      ...loadings,
      sshTestLoading: true,
    });
    connectionService
      .testSSH(p)
      .then(() => {
        staticMessage.success(i18n('connection.message.testConnectResult', i18n('common.text.successful')));
      })
      .finally(() => {
        setLoading({
          ...loadings,
          sshTestLoading: false,
        });
      });
  }

  return (
    <div ref={ref as any} className={styles.connectionBox}>
      <div className={styles.formBody}>
        <div className={styles.baseInfoBox}>
          <RenderForm
            dataSourceFormConfigProps={dataSourceFormConfigPropsMemo}
            backfillData={backfillData!}
            form={baseInfoForm}
            tab="baseInfo"
            disabled={backfillData.isAdmin === false}
          />
        </div>
        <Collapse defaultActiveKey={['driver']} items={getItems()} />
      </div>
      <div className={styles.formFooter}>
        <div className={styles.test}>
          {
            <Button
              loading={loadings.testButton}
              onClick={saveConnection.bind(null, submitType.TEST)}
              className={styles.test}
            >
              {i18n('connection.button.testConnection')}
            </Button>
          }
        </div>
        <div className={styles.rightButton}>
          <Button onClick={onCancel} className={styles.cancel}>
            {i18n('common.button.cancel')}
          </Button>
          {backfillData.isAdmin !== false && (
            <Button
              className={styles.save}
              type="primary"
              loading={loadings.confirmButton}
              onClick={saveConnection.bind(null, backfillData.id ? submitType.UPDATE : submitType.SAVE)}
            >
              {backfillData.id ? i18n('common.button.modify') : i18n('common.button.save')}
            </Button>
          )}
        </div>
      </div>
    </div>
  );
});

export default ConnectionEdit;

interface IRenderFormProps {
  tab: ITabsType;
  form: any;
  backfillData: IConnectionDetails;
  dataSourceFormConfigProps: IConnectionConfig;
  disabled: boolean;
}

function RenderForm(props: IRenderFormProps) {
  const { tab, form, backfillData, dataSourceFormConfigProps } = props;
  const curLanguage = useGlobalStore.getState().baseSetting.language;
  const defaultLabelWidth: Record<LangType, string> = {
    [LangType.EN_US]: '110px',
    [LangType.ZH_CN]: '70px',
    [LangType.JA_JP]: '100px',
    [LangType.ES_ES]: '110px',
    [LangType.KO_KR]: '100px',
  };

  let aliasChanged = false;

  const [dataSourceFormConfig, setDataSourceFormConfig] = useState<IConnectionConfig>(dataSourceFormConfigProps);
  const formDataRef = React.useRef<any>(null);

  useEffect(() => {
    form.resetFields();
    changeDataSourceFormConfig(backfillData);
    form.setFieldsValue(backfillData);
    formDataRef.current = backfillData;
  }, [backfillData.id, backfillData.type]);

  useEffect(() => {
    setDataSourceFormConfig(dataSourceFormConfigProps);
  }, [dataSourceFormConfigProps]);

  const initialValuesMemo = useMemo(() => {
    return initialFormData(dataSourceFormConfigProps[tab]?.items);
  }, []);

  const [initialValues] = useState(initialValuesMemo);

  useEffect(() => {
    if (!backfillData) {
      return;
    }
    if (tab === 'baseInfo') {
      regEXFormatting({ url: backfillData.url }, backfillData);
    }
    if (tab === 'ssh') {
      regEXFormatting({}, backfillData.ssh || {});
    }
    if (tab === 'driver') {
      regEXFormatting({}, backfillData.driverConfig || {});
    }
  }, [backfillData]);

  function changeDataSourceFormConfig(_backfillData: any) {
    // Iterate through every item here.
    dataSourceFormConfig.ssh.items.forEach((t: IFormItem) => {
      if (t.selects) {
        t.defaultValue = _backfillData?.ssh?.[t.name] || t.defaultValue;
      }
    });
    dataSourceFormConfig.baseInfo.items.forEach((t: IFormItem) => {
      if (t.selects) {
        t.defaultValue = _backfillData[t.name] || t.defaultValue;
        t.selects.forEach((selectItem: ISelect) => {
          // Invoke the callback inside Select.
          if (selectItem.value === t.defaultValue) {
            if (selectItem.onChange) {
              setDataSourceFormConfig(selectItem.onChange({ ...dataSourceFormConfig }));
            }
          }
        });
      }
    });
  }

  function initialFormData(_dataSourceFormConfig: IFormItem[] | undefined) {
    let initValue: any = {};
    _dataSourceFormConfig?.map((t) => {
      initValue[t.name] = t.defaultValue;
      if (t.selects?.length) {
        t.selects?.map((item) => {
          if (item.value === t.defaultValue) {
            initValue = {
              ...initValue,
              ...initialFormData(item.items),
            };
          }
        });
      }
    });
    return initValue;
  }

  function onFieldsChange(data: any, datas: any) {
    // Convert the Ant Design format into a plain object.
    if (!data.length) {
      return;
    }
    const keyName = data[0].name[0];
    const keyValue = data[0].value;
    const variableData = {
      [keyName]: keyValue,
    };
    const dataObj: any = {};
    datas.map((t: any) => {
      dataObj[t.name[0]] = t.value;
    });

    const finalData = {
      ...(formDataRef.current || {}),
      ...dataObj,
    };

    formDataRef.current = finalData;

    // Parse or construct the URL with regular expressions.
    if (tab === 'baseInfo') {
      regEXFormatting(variableData, finalData);
    }
  }

  function extractObj(url: any) {
    const { template, pattern } = dataSourceFormConfig.baseInfo;
    // Extract the value associated with each keyword.
    const matches = url.match(pattern)!;
    // Extract keyword keys from braces.
    const reg = /{(.*?)}/g;
    let match: any;
    const arr: any = [];
    while ((match = reg.exec(template)) !== null) {
      arr.push(match[1]);
    }
    // Match each key with its value.
    const newExtract: any = {};
    arr.map((t, i) => {
      const value = t === 'database' ? matches[i + 2] || '' : matches[i + 1];
      newExtract[t] = t === 'host' ? normalizeJdbcHostFromUrl(value) : value;
    });
    return newExtract;
  }

  function regEXFormatting(
    variableData: { [key: string]: any },
    dataObj: { [key: string]: any },
    _dataSourceFormConfig?: IConnectionConfig,
  ) {
    const { template, pattern } = (_dataSourceFormConfig || dataSourceFormConfig).baseInfo;
    const keyName = Object.keys(variableData)[0];
    const keyValue = variableData[Object.keys(variableData)[0]];
    let newData: any = {};

    if (!shouldSyncJdbcUrlForField(keyName, template)) {
      return;
    }

    if (keyName === 'url') {
      // First check whether the URL matches the expected expression.
      if (pattern.test(keyValue)) {
        newData = extractObj(keyValue);
        const formattedHost = formatJdbcHostForUrl(newData.host);
        if (newData.host && formattedHost !== newData.host && !String(keyValue).includes(formattedHost)) {
          newData.url = String(keyValue).replace(newData.host, formattedHost);
        }
      }
    } else if (keyName === 'alias') {
      aliasChanged = true;
    } else {
      // Update the URL above.
      let url = template;
      const normalizedDataObj = {
        ...dataObj,
        ...(dataObj.host ? { host: normalizeJdbcHostFromUrl(dataObj.host) } : {}),
      };
      Object.keys(normalizedDataObj).map((t) => {
        const value = t === 'host' ? formatJdbcHostForUrl(normalizedDataObj[t]) : normalizedDataObj[t];
        url = url.replace(`{${t}}`, value || '');
      });
      newData = {
        url,
      };
      if (keyName === 'host') {
        newData.host = normalizedDataObj.host;
      }
    }

    if (keyName === 'host' && !aliasChanged) {
      newData.alias = '@' + normalizeJdbcHostFromUrl(keyValue);
    }

    const nextFieldsValue = {
      ...dataObj,
      ...newData,
    };
    form.setFieldsValue(nextFieldsValue);
    formDataRef.current = {
      ...(formDataRef.current || {}),
      ...nextFieldsValue,
    };
  }

  function renderFormItem(t: IFormItem): React.ReactNode {
    if (t.hidden) {
      return null;
    }
    const labelText = t.labelKey ? i18n(t.labelKey) : resolveLocalizedConnectionText(t.labelName, curLanguage);
    const helpText = t.helpKey ? i18n(t.helpKey) : undefined;
    const label = helpText ? (
      <span className={styles.formLabelWithHelp}>
        <span>{labelText}</span>
        <Tooltip title={helpText} mouseEnterDelay={0.2}>
          <span className={styles.formHelpIcon} role="img" tabIndex={0} aria-label={helpText}>
            <CircleHelp aria-hidden="true" size={14} />
          </span>
        </Tooltip>
      </span>
    ) : (
      labelText
    );
    const name = t.name;
    const isIdentitySetting = t.layoutGroup === 'dataSourceIdentity';
    const width = isIdentitySetting ? undefined : t?.styles?.width || '100%';
    const labelWidth =
      t?.styles?.labelWidth?.[curLanguage] ||
      t?.styles?.labelWidth?.[LangType.EN_US] ||
      defaultLabelWidth[curLanguage];
    const placeholder = t.placeholderKey
      ? i18n(t.placeholderKey)
      : resolveLocalizedConnectionText(t.placeholder, curLanguage);
    const labelAlign: any = t?.styles?.labelAlign || 'left';

    function handleFormItemValueChange(value: any) {
      const variableData = {
        [name]: value,
      };
      const finalData = {
        ...(formDataRef.current || {}),
        ...form.getFieldsValue(),
        ...variableData,
      };
      formDataRef.current = finalData;

      if (tab === 'baseInfo') {
        regEXFormatting(variableData, finalData);
        return;
      }

      form.setFieldsValue(variableData);
    }

    const inputControl = (
      <Input disabled={props.disabled} maxLength={t.maxLength} placeholder={placeholder} />
    );
    const textareaControl = t.fileContentMode ? (
      <FileContentTextArea
        disabled={props.disabled}
        fileTypes={t.fileTypes}
        maxLength={t.maxLength}
        mode={t.fileContentMode}
        placeholder={placeholder}
        rows={t.rows || 3}
        onChange={handleFormItemValueChange}
      />
    ) : (
      <Input.TextArea
        disabled={props.disabled}
        maxLength={t.maxLength}
        placeholder={placeholder}
        rows={t.rows || 3}
      />
    );
    const selectControl = (
      <Select
        placeholder={placeholder}
        value={t.defaultValue}
        disabled={t?.disabled}
        onChange={(e) => {
          t.selects?.forEach((selectItem) => {
            if (selectItem.value === e) {
              let _dataSourceFormConfig = { ...dataSourceFormConfigProps };
              if (selectItem.onChange) {
                _dataSourceFormConfig = selectItem.onChange(_dataSourceFormConfig);
              }

              _dataSourceFormConfig[tab]?.items.map((j) => {
                if (j.name === name) {
                  j.defaultValue = selectItem.value;
                }
              });
              setDataSourceFormConfig(_dataSourceFormConfig);
              regEXFormatting({ [name]: e }, formDataRef.current, _dataSourceFormConfig);
            }
          });
        }}
      >
        {t.selects?.map((selectItem: any) => (
          <Option key={selectItem.value?.toString()} value={selectItem.value}>
            <div className={styles.optionItem}>
              {selectItem?.color && (
                <div className={styles.envTag} style={{ background: selectItem?.color.toLocaleLowerCase() }} />
              )}
              {localizeConnectionFormText(selectItem.label, curLanguage)}
            </div>
          </Option>
        ))}
      </Select>
    );

    const renderIdentityFormControl = () => {
      if (t.inputType === InputType.CHECKBOX) {
        return (
          <Form.Item name={name} valuePropName="checked" noStyle>
            <Checkbox aria-label={labelText}>{label}</Checkbox>
          </Form.Item>
        );
      }

      const controls: Partial<Record<InputType, React.ReactNode>> = {
        [InputType.INPUT]: inputControl,
        [InputType.TEXTAREA]: textareaControl,
        [InputType.SELECT]: selectControl,
        [InputType.COLOR]: (
          <DataSourceColorPicker
            disabled={props.disabled}
            placement="bottomLeft"
            responsive
            showLabel={false}
          />
        ),
      };

      return (
        <div className={styles.identitySettingsField}>
          <span className={styles.identitySettingsLabel}>{label}</span>
          <Form.Item name={name} noStyle>
            {controls[t.inputType]}
          </Form.Item>
        </div>
      );
    };

    const FormItemTypes: { [key in InputType]: () => React.ReactNode } = {
      [InputType.INPUT]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          {inputControl}
        </Form.Item>
      ),

      [InputType.TEXTAREA]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          {textareaControl}
        </Form.Item>
      ),

      [InputType.FILE]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          <FilePathInput
            disabled={props.disabled}
            fileTypes={t.fileTypes}
            placeholder={placeholder}
            onChange={handleFormItemValueChange}
          />
        </Form.Item>
      ),

      [InputType.SELECT]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          {selectControl}
        </Form.Item>
      ),

      [InputType.PASSWORD]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          <Input.Password />
        </Form.Item>
      ),

      [InputType.COLOR]: () => (
        <Form.Item
          label={label}
          name={name}
          style={{ '--form-label-width': labelWidth } as any}
          labelAlign={labelAlign}
        >
          <DataSourceColorPicker disabled={props.disabled} placement="bottomLeft" showLabel={false} />
        </Form.Item>
      ),

      [InputType.CHECKBOX]: () => (
        <Form.Item
          name={name}
          valuePropName="checked"
        >
          <Checkbox aria-label={labelText}>{label}</Checkbox>
        </Form.Item>
      ),
    };

    const identityItemClassName = classnames(styles.identitySettingsItem, {
      [styles.identitySettingsEnvironment]: name === 'environmentId',
      [styles.identitySettingsColor]: name === 'identityColor',
      [styles.identitySettingsWatermark]: name === 'watermarkEnabled',
      [styles.identitySettingsContent]: name === 'watermarkContent',
    });

    const renderedItem = (
      <Fragment>
        <div
          key={t.name}
          className={classnames(
            { [styles.labelTextAlign]: t.labelTextAlign },
            isIdentitySetting && identityItemClassName,
          )}
          style={width ? { width } : undefined}
        >
          {isIdentitySetting && name !== 'environmentId'
            ? renderIdentityFormControl()
            : FormItemTypes[t.inputType]()}
        </div>
        {t.selects?.map((item) => {
          if (t.defaultValue === item.value) {
            return item.items?.map((nestedItem) => {
              return renderFormItem(nestedItem);
            });
          }
        })}
      </Fragment>
    );

    if (!t.visibleWhen) {
      return <Fragment key={t.name}>{renderedItem}</Fragment>;
    }

    return (
      <Form.Item
        key={`${t.name}-visibility`}
        noStyle
        shouldUpdate={(previous, current) => previous[t.visibleWhen!.name] !== current[t.visibleWhen!.name]}
      >
        {({ getFieldValue }) => {
          const visibleValue = getFieldValue(t.visibleWhen!.name);
          const visibleValues = t.visibleWhen!.values || [t.visibleWhen!.value];
          return visibleValues.includes(visibleValue) ? (
            renderedItem
          ) : (
            <div
              aria-hidden="true"
              className={classnames(
                isIdentitySetting && identityItemClassName,
                isIdentitySetting && styles.identitySettingsPlaceholder,
              )}
              style={width ? { width } : undefined}
            />
          );
        }}
      </Form.Item>
    );
  }

  const portField = dataSourceFormConfig[tab]!.items.find((item) => item.name === 'port');
  const portLabelWidth =
    portField?.styles?.labelWidth?.[curLanguage] ||
    portField?.styles?.labelWidth?.[LangType.EN_US] ||
    defaultLabelWidth[curLanguage];

  return (
    <Form
      colon={false}
      name={tab}
      form={form}
      initialValues={initialValues}
      className={styles.form}
      autoComplete="off"
      labelAlign="left"
      onFieldsChange={onFieldsChange}
      disabled={props.disabled}
      style={{ '--identity-port-label-width': portLabelWidth } as any}
    >
      {dataSourceFormConfig[tab]!.items.reduce<React.ReactNode[]>((renderedItems, item, index, items) => {
        if (item.layoutGroup !== 'dataSourceIdentity') {
          renderedItems.push(renderFormItem(item));
          return renderedItems;
        }
        if (index > 0 && items[index - 1].layoutGroup === item.layoutGroup) {
          return renderedItems;
        }
        renderedItems.push(
          <div className={styles.identitySettingsRow} key={item.layoutGroup}>
            {items.filter((groupItem) => groupItem.layoutGroup === item.layoutGroup).map(renderFormItem)}
          </div>,
        );
        return renderedItems;
      }, [])}
    </Form>
  );
}

interface IRenderExtendTableProps {
  backfillData: IConnectionDetails;
}

let extendTableData: any = [];

interface IExtendTable {
  key: number;
  label: string;
  value: string;
}

function RenderExtendTable(props: IRenderExtendTableProps) {
  const { backfillData } = props;
  const databaseType = backfillData.type;
  const [data, setData] = useState<IExtendTable[]>([{ key: 0, label: '', value: '' }]);
  const dataSourceFormConfigMemo = useMemo<IConnectionConfig>(() => {
    return resolveDataSourceFormConfig(databaseType);
  }, [backfillData.type]);
  // Disable editing.
  const disabled = backfillData.isAdmin === false;

  useEffect(() => {
    const extendInfoList = backfillData?.extendInfo?.length
      ? backfillData?.extendInfo
      : dataSourceFormConfigMemo.extendInfo;

    const extendInfo =
      extendInfoList?.map((t, i) => {
        return {
          key: i,
          label: t.key,
          value: t.value,
        };
      }) || [];

    setData([...extendInfo, { key: extendInfo.length, label: '', value: '' }]);
  }, [dataSourceFormConfigMemo, backfillData]);

  useEffect(() => {
    extendTableData = data;
  }, [data]);

  const columns: any = [
    {
      title: i18n('connection.tableHeader.name'),
      dataIndex: 'label',
      width: '60%',
      render: (value: any, row: any, index: number) => {
        let isCustomLabel = true;

        dataSourceFormConfigMemo.extendInfo?.map((item) => {
          if (item.key === row.label) {
            isCustomLabel = false;
          }
        });

        function change(e: any) {
          const newData = [...data];
          newData[index] = {
            key: index,
            label: e.target.value,
            value: '',
          };
          setData(newData);
        }

        function blur() {
          const newData: any = [];
          data.map((t) => {
            if (t.label) {
              newData.push(t);
            }
          });
          if (index === data.length - 1 && row.label) {
            newData[index] = {
              key: index,
              label: row.label,
              value: '',
            };
          }
          setData([...newData, { key: newData.length, label: '', value: '' }]);
        }

        if (index === data.length - 1 || isCustomLabel) {
          return (
            <Input
              disabled={disabled}
              onBlur={blur}
              placeholder={index === data.length - 1 ? i18n('common.text.custom') : ''}
              onChange={change}
              value={value}
            />
          );
        } else {
          return <span>{value}</span>;
        }
      },
    },
    {
      title: i18n('connection.tableHeader.statistics'),
      dataIndex: 'value',
      width: '40%',
      render: (value: any, row: any, index: number) => {
        function change(e: any) {
          const newData = [...data];
          newData[index] = {
            key: index,
            label: row.label,
            value: e.target.value,
          };
          setData(newData);
        }

        if (index === data.length - 1) {
          return <Input onBlur={blur} disabled placeholder="<value>" onChange={change} value={value} />;
        } else {
          return <Input disabled={disabled} onChange={change} value={value} />;
        }
      },
    },
  ];

  return (
    <div className={styles.extendTable}>
      <Table bordered size="small" pagination={false} columns={columns} dataSource={data} />
    </div>
  );
}
