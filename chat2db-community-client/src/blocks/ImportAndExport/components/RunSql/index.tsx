import { memo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input, InputNumber, Select } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { isDevelopment } from '@/utils/env';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { ImportTaskParams } from '@/service/importExport';
import { normalizeRunSqlFormValues, RunSqlFormValues } from './options';

interface IProps {
  className?: string;
  setIsReady?: (p: boolean) => void;
}

export interface RunSqlRef {
  getValues: () => ImportTaskParams | null;
}

// const codeOptions = [
//   {
//     label: 'UTF-8',
//     value: 'UTF-8',
//   },
//   {
//     label: 'GB2312',
//     value: 'GB2312',
//   },
// ];

const RunSql = forwardRef((props: IProps, ref: ForwardedRef<RunSqlRef>) => {
  const { setIsReady } = props;
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const [fileUrlList, setFileUrlList] = useState<string[]>([]);
  const [formValues, setFormValues] = useState<RunSqlFormValues>({
    encoding: 'UTF-8',
    errorPolicy: 'STOP',
    commitMode: 'SCRIPT',
    batchSize: 1000,
  });

  useEffect(() => {
    setIsReady && setIsReady(!!(fileUrlList.length || formValues.fileUrl));
  }, [fileUrlList, formValues]);

  const { runSqlBoundInfo } = useImportExportStore((state) => {
    return {
      runSqlBoundInfo: state.runSqlBoundInfo,
    };
  });

  useEffect(() => {
    if (!runSqlBoundInfo) return;

    const _executionEnvironment = [
      runSqlBoundInfo.dataSourceName,
      runSqlBoundInfo.databaseName,
      runSqlBoundInfo.schemaName,
    ]
      .filter(Boolean)
      .join('/');

    form.setFieldsValue({
      executionEnvironment: _executionEnvironment,
    });
  }, [runSqlBoundInfo]);

  useImperativeHandle(ref, () => ({
    getValues: () => {
      if (!runSqlBoundInfo?.dataSourceId) return null;
      const { dataSourceId, databaseName, schemaName } = runSqlBoundInfo;
      const sourceFile = fileUrlList[0] || formValues.fileUrl;
      if (!sourceFile) return null;
      return {
        dataSourceId,
        databaseName,
        schemaName,
        taskType: ImportExportTaskType.SQL_FILE_IMPORT,
        sourceFile,
        format: ImportExportFileType.SQL,
        encoding: formValues.encoding,
        errorPolicy: formValues.errorPolicy,
        commitMode: formValues.commitMode,
        batchSize: formValues.batchSize,
      };
    },
  }));

  const handleFileUrlListChange = (_fileUrlList) => {
    setFileUrlList(_fileUrlList.map((item) => item.filePath));
  };

  return (
    <Form
      className={styles.form}
      layout="vertical"
      form={form}
      initialValues={formValues}
      autoComplete="off"
      onValuesChange={(changedValues, values) => {
        const nextValues = normalizeRunSqlFormValues(values);
        if (changedValues.commitMode === 'SINGLE_TRANSACTION' && values.errorPolicy !== 'STOP') {
          form.setFieldsValue({ errorPolicy: 'STOP' });
        }
        setFormValues(nextValues);
      }}
    >
      <Form.Item label={`${i18n('workspace.importExport.executionEnvironment')}:`} name="executionEnvironment">
        <Input autoComplete="off" disabled />
      </Form.Item>
      <Form.Item>
        <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept=".sql" />
      </Form.Item>
      <Form.Item label={`${i18n('workspace.importExport.encoding')}:`} name="encoding">
        <Select
          options={[
            { value: 'UTF-8', label: 'UTF-8' },
            { value: 'GB18030', label: 'GB18030' },
            { value: 'ISO-8859-1', label: 'ISO-8859-1' },
          ]}
        />
      </Form.Item>
      <Form.Item label={`${i18n('workspace.importExport.errorPolicy')}:`} name="errorPolicy">
        <Select
          disabled={formValues.commitMode === 'SINGLE_TRANSACTION'}
          options={[
            { value: 'STOP', label: i18n('workspace.importExport.errorPolicyStop') },
            { value: 'CONTINUE', label: i18n('workspace.importExport.errorPolicyContinue') },
          ]}
        />
      </Form.Item>
      <Form.Item label={`${i18n('workspace.importExport.commitMode')}:`} name="commitMode">
        <Select
          options={[
            { value: 'SCRIPT', label: i18n('workspace.importExport.commitModeScript') },
            { value: 'BATCH', label: i18n('workspace.importExport.commitModeBatch') },
            { value: 'SINGLE_TRANSACTION', label: i18n('workspace.importExport.commitModeSingle') },
          ]}
        />
      </Form.Item>
      {formValues.commitMode === 'BATCH' && (
        <Form.Item label={`${i18n('workspace.importExport.batchSize')}:`} name="batchSize">
          <InputNumber min={1} max={100000} style={{ width: '100%' }} />
        </Form.Item>
      )}
      {isDevelopment && (
        <Form.Item label="File URL" name="fileUrl">
          <Input autoComplete="off" />
        </Form.Item>
      )}
      {/* <Form.Item label={`Encoding:`} name="code">
        <Select options={codeOptions} />
      </Form.Item> */}
      {/* <div className={styles.checkboxBody}>
        <Form.Item name="errorContinue" valuePropName="checked">
          <Checkbox value="Y">Continue on error</Checkbox>
        </Form.Item>
        <Form.Item name="runMultiple" valuePropName="checked">
          <Checkbox>Run multiple queries in each execution</Checkbox>
        </Form.Item>
        <Form.Item name="AUTOCOMMIT" valuePropName="checked">
          <Checkbox>SET AUTOCOMMIT=0</Checkbox>
        </Form.Item>
      </div> */}
    </Form>
  );
});

export default memo(RunSql);
