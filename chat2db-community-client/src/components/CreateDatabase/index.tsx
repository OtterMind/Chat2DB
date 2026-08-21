import { useCallback, useMemo, useState, useEffect, useRef } from 'react';
import { Form, Grid, Input, Modal, Select, type InputRef } from 'antd';
import MonacoEditor, { IExportRefFunction } from '@/components/MonacoEditor';
import { v4 as uuid } from 'uuid';
import sqlService from '@/service/sql';
import i18n from '@/i18n';
import { debounce } from 'lodash';
import { DatabaseTypeCode } from '@/constants';
import { useWorkspaceStore } from '@/store/workspace';
import { canSetCreateDatabaseCharset, canSetCreateDatabaseCollation } from '@/utils/databaseJudgments';
import type { ICharset, ICollation } from '@/typings';
import { buildCharsetOptions, buildCollationOptions } from './options';
import { useStyles } from './style';
import type { IDdlExecuteRequest } from '@/service/dmlRequest';

// TODO: This can warn that the `useForm` instance is not connected to a Form element.
// The !!relyOnParams && condition can prevent the form from being created.
// Needs resolution.

interface IProps {
  relyOnParams: {
    databaseType: DatabaseTypeCode;
    dataSourceId: number;
    databaseName?: string;
  };
  executedCallback?: () => void;
}

export type CreateType = 'database' | 'schema';

export interface ICreateDatabase {
  databaseName?: string;
  schemaName?: string;
  comment?: string;
  charset?: string;
  collation?: string;
}

// Databases that do not support comments during creation.
const noCommentDatabase = [DatabaseTypeCode.MYSQL];

const CreateDatabase = () => {
  const { styles } = useStyles();
  const [form] = Form.useForm<ICreateDatabase>();
  const monacoEditorUuid = useMemo(() => uuid(), []);
  const monacoEditorRef = useRef<IExportRefFunction>(null);
  const previewRequestIdRef = useRef(0);
  const [open, setOpen] = useState(false);
  const [errorMessage, setErrorMessage] = useState<{ success: boolean; message: string; originalSql: string } | null>(
    null,
  );
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [createType, setCreateType] = useState<CreateType>('database');
  const [relyOnParams, setRelyOnParams] = useState<IProps['relyOnParams'] | null>(null);
  const executedCallbackRef = useRef<IProps['executedCallback']>();
  const setOpenCreateDatabaseModal = useWorkspaceStore((state) => state.setOpenCreateDatabaseModal);
  const inputRef = useRef<InputRef>(null);
  const [charsets, setCharsets] = useState<ICharset[]>([]);
  const [collations, setCollations] = useState<ICollation[]>([]);
  const [databaseOptionsLoading, setDatabaseOptionsLoading] = useState(false);
  const [selectedCharset, setSelectedCharset] = useState<string>();
  const [previewReady, setPreviewReady] = useState(false);
  const screens = Grid.useBreakpoint();

  const supportsCharset = canSetCreateDatabaseCharset(relyOnParams?.databaseType);
  const supportsCollation = canSetCreateDatabaseCollation(relyOnParams?.databaseType);

  const charsetOptions = useMemo(() => buildCharsetOptions(charsets), [charsets]);
  const collationOptions = useMemo(
    () => buildCollationOptions(charsets, collations, selectedCharset),
    [charsets, collations, selectedCharset],
  );

  useEffect(() => {
    if (open) {
      setTimeout(() => {
        inputRef.current?.focus();
      }, 0);
    }
  }, [open]);

  const resetModalState = () => {
    previewRequestIdRef.current += 1;
    setErrorMessage(null);
    setSelectedCharset(undefined);
    setPreviewReady(false);
    form.resetFields();
    monacoEditorRef.current?.setValue('', 'cover');
  };

  const closeModal = () => {
    resetModalState();
    setOpen(false);
  };

  useEffect(() => {
    if (!open || createType !== 'database' || !relyOnParams || (!supportsCharset && !supportsCollation)) {
      setCharsets([]);
      setCollations([]);
      setDatabaseOptionsLoading(false);
      return;
    }

    let active = true;
    setCharsets([]);
    setCollations([]);
    setDatabaseOptionsLoading(true);
    sqlService
      .getDatabaseFieldTypeList({
        dataSourceId: relyOnParams.dataSourceId,
        databaseName: relyOnParams.databaseName || '',
      })
      .then((result) => {
        if (!active) {
          return;
        }
        setCharsets(result?.charsets || []);
        setCollations(result?.collations || []);
      })
      .catch(() => {
        if (active) {
          setCharsets([]);
          setCollations([]);
        }
      })
      .finally(() => {
        if (active) {
          setDatabaseOptionsLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [open, createType, relyOnParams?.dataSourceId, relyOnParams?.databaseName, supportsCharset, supportsCollation]);

  const config = useMemo(() => {
    return createType === 'database'
      ? {
          title: `${i18n('common.title.create')} Database`,
          api: sqlService.getCreateDatabaseSql,
          formName: 'databaseName',
        }
      : {
          title: `${i18n('common.title.create')} Schema`,
          api: sqlService.getCreateSchemaSql,
          formName: 'schemaName',
        };
  }, [createType]);

  const labelCol = screens.sm === false ? undefined : { flex: '150px' };

  const handleCharsetChange = (charset?: string) => {
    setSelectedCharset(charset);
    const defaultCollation = charsets.find((item) => item.charsetName === charset)?.defaultCollationName;
    form.setFieldValue('collation', defaultCollation || undefined);
  };

  const schedulePreview = useMemo(
    () =>
      debounce(() => {
        const requestId = previewRequestIdRef.current;
        const formData: ICreateDatabase = form.getFieldsValue();
        if (!formData.databaseName && createType === 'database') {
          return;
        }
        if (!formData.schemaName && createType === 'schema') {
          return;
        }
        const params = {
          databaseType: relyOnParams?.databaseType,
          dataSourceId: relyOnParams?.dataSourceId,
          databaseName: relyOnParams?.databaseName,
          ...formData,
        };
        config
          .api(params as any)
          .then((res) => {
            if (requestId !== previewRequestIdRef.current) {
              return;
            }
            const { sql } = res;
            monacoEditorRef.current?.setValue(sql, 'cover');
            setPreviewReady(Boolean(sql?.trim()));
          })
          .catch(() => {
            if (requestId === previewRequestIdRef.current) {
              setPreviewReady(false);
            }
          });
      }, 500),
    [form, relyOnParams, createType, config],
  );

  const handleFieldsChange = useCallback(() => {
    previewRequestIdRef.current += 1;
    setErrorMessage(null);
    setPreviewReady(false);
    schedulePreview();
  }, [schedulePreview]);

  useEffect(() => {
    return () => {
      schedulePreview.cancel();
      previewRequestIdRef.current += 1;
    };
  }, [schedulePreview]);

  const executeUpdateDataSql = (sql: string) => {
    if (relyOnParams == null) {
      return;
    }
    const params: IDdlExecuteRequest = {
      dataSourceId: relyOnParams.dataSourceId,
      databaseName: relyOnParams.databaseName,
      sql,
    };
    setConfirmLoading(true);
    setErrorMessage(null);
    return sqlService
      .executeDDL(params)
      .then((res) => {
        if (res.success) {
          closeModal();
          executedCallbackRef.current?.();
        } else {
          setErrorMessage(res);
        }
      })
      .finally(() => {
        setConfirmLoading(false);
      });
  };

  const onOk = () => {
    const sql = monacoEditorRef.current?.getAllContent() || '';
    executeUpdateDataSql(sql);
  };

  const openCreateDatabaseModal = (params: {
    type: CreateType;
    relyOnParams: {
      databaseType: DatabaseTypeCode;
      dataSourceId: number;
      databaseName?: string;
    };
    executedCallback?: () => void;
  }) => {
    setOpen(true);
    setCreateType(params.type);
    setRelyOnParams(params.relyOnParams);
    executedCallbackRef.current = params.executedCallback;
  };

  useEffect(() => {
    setOpenCreateDatabaseModal(openCreateDatabaseModal);
  }, []);

  return (
    !!relyOnParams && (
      <Modal
        onCancel={() => {
          closeModal();
        }}
        maskClosable={false}
        title={config.title}
        destroyOnClose
        confirmLoading={confirmLoading}
        okButtonProps={{ disabled: !previewReady }}
        open={open}
        onOk={onOk}
      >
        <div className={styles.createDatabaseDom}>
          <Form
            labelAlign="left"
            layout={screens.sm === false ? 'vertical' : 'horizontal'}
            form={form}
            labelCol={labelCol}
            onFieldsChange={handleFieldsChange}
            name="create"
          >
            <Form.Item label={i18n('common.label.name')} name={config.formName}>
              <Input ref={inputRef} autoComplete="off" />
            </Form.Item>
            {noCommentDatabase.includes(relyOnParams.databaseType) ? null : (
              <Form.Item label={i18n('common.label.comment')} name="comment">
                <Input autoComplete="off" />
              </Form.Item>
            )}
            {createType === 'database' && supportsCharset ? (
              <Form.Item label={i18n('editTable.label.characterSet')} name="charset">
                <Select
                  allowClear
                  showSearch
                  loading={databaseOptionsLoading}
                  options={charsetOptions}
                  onChange={handleCharsetChange}
                  optionFilterProp="label"
                />
              </Form.Item>
            ) : null}
            {createType === 'database' && supportsCollation ? (
              <Form.Item label={i18n('editTable.label.collation')} name="collation">
                <Select
                  allowClear
                  showSearch
                  loading={databaseOptionsLoading}
                  options={collationOptions}
                  optionFilterProp="label"
                />
              </Form.Item>
            ) : null}
          </Form>
          <div className={styles.previewBox}>
            <div className={styles.previewText}>{i18n('common.title.preview')}</div>
          </div>
          <div className={styles.monacoEditorBox}>
            <MonacoEditor
              ref={monacoEditorRef}
              options={{
                lineNumbers: 'off',
              }}
              autoFocus={false}
              id={monacoEditorUuid}
            />
          </div>
          {errorMessage && (
            <div className={styles.errorBox} role="alert">
              <div className={styles.errorTitle}>{i18n('common.title.errorMessage')}</div>
              <div className={styles.errorMessage}>{errorMessage.message}</div>
            </div>
          )}
        </div>
      </Modal>
    )
  );
};

export default CreateDatabase;
