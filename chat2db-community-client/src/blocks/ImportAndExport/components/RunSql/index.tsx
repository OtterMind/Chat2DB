import SqlImportOptionsFields from '../SqlImportOptionsFields';
import { DEFAULT_SQL_IMPORT_OPTIONS } from '../../utils/importOptions';
import { memo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { isDevelopment } from '@/utils/env';
import { ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { ImportTaskParams } from '@/service/importExport';

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
  const [sqlImportOptions, setSqlImportOptions] = useState(DEFAULT_SQL_IMPORT_OPTIONS);
  const [fileUrlList, setFileUrlList] = useState<string[]>([]);
  const [formValues, setFormValues] = useState<any>({});

  useEffect(() => {
    setIsReady?.(!!(fileUrlList.length || formValues.fileUrl));
  }, [fileUrlList, formValues, setIsReady]);

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
      if (!runSqlBoundInfo) return null;
      const { dataSourceId, databaseName, schemaName } = runSqlBoundInfo;
      return {
        dataSourceId,
        databaseName,
        schemaName,
        taskType: ImportExportTaskType.SQL_FILE_IMPORT,
        sourceFile: fileUrlList[0] || formValues.fileUrl,
        format: ImportExportFileType.SQL,
        sqlImportOptions,
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
      autoComplete="off"
      onFieldsChange={() => {
        setFormValues(form.getFieldsValue());
      }}
    >
      <Form.Item
        label={`${i18n('workspace.importExport.executionEnvironment')}:`}
        name="executionEnvironment"
      >
        <Input autoComplete="off" disabled />
      </Form.Item>
      <Form.Item>
        <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept=".sql" />
      </Form.Item>
      <SqlImportOptionsFields value={sqlImportOptions} onChange={setSqlImportOptions} />
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
