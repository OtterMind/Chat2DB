import { memo, useMemo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input, Select, InputNumber } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { IconButton } from '@chat2db/ui';
import { ImportExportType, ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { ExportTaskParams, ImportTaskParams } from '@/service/importExport';
import { IImportOptions } from '@/typings/importExport';
import { isDesktop, isDevelopment } from '@/utils/env';
import jcefApi from '@/jcef';

interface IProps {
  className?: string;
  setIsReady?: (p: boolean) => void;
}

export interface ImportExportFileRef {
  getValues: () => ExportTaskParams | ImportTaskParams | null;
}

interface ImportExportFormValue {
  exportType: ImportExportFileType;
  containsHeader: boolean;
  fileUrl?: string;
  compression?: string;
  checkpointRows?: number;
  charset?: string;
  delimiter?: string;
  quoteChar?: string;
  skipRows?: number;
  nullString?: string;
  onError?: 'ABORT' | 'SKIP';
  maxErrors?: number;
}

const exportTypeOptions = [
  { label: 'CSV', value: ImportExportFileType.CSV, accept: '.csv' },
  { label: 'XLSX', value: ImportExportFileType.XLSX, accept: '.xlsx' },
  { label: 'XLS', value: ImportExportFileType.XLS, accept: '.xls' },
  { label: 'JSON', value: ImportExportFileType.JSON, accept: '.json' },
  { label: 'NDJSON', value: ImportExportFileType.NDJSON, accept: '.ndjson' },
  { label: 'Markdown', value: ImportExportFileType.MARKDOWN, accept: '.md' },
  { label: 'SQL', value: ImportExportFileType.SQL, accept: '.sql' },
];

// The import backend parses these formats; NDJSON/Markdown are export-only.
const importTypeOptions = exportTypeOptions.filter(
  (option) =>
    option.value !== ImportExportFileType.NDJSON && option.value !== ImportExportFileType.MARKDOWN,
);

// Formats that can be checkpointed for resumable export.
const checkpointableFormats = [
  ImportExportFileType.CSV,
  ImportExportFileType.NDJSON,
  ImportExportFileType.MARKDOWN,
  ImportExportFileType.SQL,
];

const ImportExportFile = forwardRef((props: IProps, ref: ForwardedRef<ImportExportFileRef>) => {
  const { setIsReady } = props;
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const [fileUrlList, setFileUrlList] = useState<string[]>([]);
  const [exportLocation, setExportLocation] = useState<string>('');
  const [formValue, setFormValue] = useState<ImportExportFormValue>({
    exportType: ImportExportFileType.CSV,
    containsHeader: true,
  });

  const { importExportDataBoundInfo } = useImportExportStore((state) => {
    return {
      importExportDataBoundInfo: state.importExportDataBoundInfo,
    };
  });

  const isImport = importExportDataBoundInfo?.type === ImportExportType.IMPORT;
  const isExport = importExportDataBoundInfo?.type === ImportExportType.EXPORT;

  useEffect(() => {
    if (importExportDataBoundInfo) {
      const { dataSourceName, databaseName, schemaName, tableName } = importExportDataBoundInfo;
      const tableNameDisplay = [dataSourceName, databaseName, schemaName, tableName].filter(Boolean).join('/');
      form.setFieldsValue({
        tableNameDisplay: tableNameDisplay,
      });
    }
  }, [importExportDataBoundInfo]);

  // Gets the corresponding file type based on the export type
  const uploadLocalFileAccept = useMemo(() => {
    return formValue.exportType ? exportTypeOptions.find((item) => item.value === formValue.exportType)?.accept : '';
  }, [formValue.exportType]);

  // file list changes
  useEffect(() => {
    if (isImport) {
      setIsReady?.(!!(fileUrlList.length || formValue.fileUrl));
    }
  }, [fileUrlList, formValue]);

  useEffect(() => {
    if (isExport) {
      setIsReady?.(!isDesktop || !!exportLocation || !!formValue.fileUrl);
    }
  }, [exportLocation, formValue]);

  const handleFileUrlListChange = (_fileUrlList) => {
    setFileUrlList(_fileUrlList.map((item) => item.filePath));
  };

  useImperativeHandle(ref, () => ({
    getValues: () => {
      if (!importExportDataBoundInfo) return null;
      const { dataSourceId, databaseName, schemaName, tableName } = importExportDataBoundInfo;
      const commonValues = {
        dataSourceId,
        databaseName,
        schemaName,
        format: formValue.exportType,
      };
      if (isExport) {
        return {
          ...commonValues,
          taskType: ImportExportTaskType.TABLE_DATA_EXPORT,
          tableNames: [tableName],
          containsHeader: formValue.containsHeader,
          exportPath: exportLocation || formValue.fileUrl,
          compression: formValue.compression || undefined,
          checkpointRows: formValue.checkpointRows || undefined,
        };
      }
      const options: IImportOptions | undefined =
        formValue.exportType === ImportExportFileType.CSV
          ? {
              charset: formValue.charset || undefined,
              delimiter: formValue.delimiter || undefined,
              quoteChar: formValue.quoteChar || undefined,
              skipRows: formValue.skipRows || undefined,
              nullString: formValue.nullString || undefined,
              onError: formValue.onError || undefined,
              maxErrors: formValue.onError === 'SKIP' ? formValue.maxErrors || undefined : undefined,
            }
          : undefined;
      return {
        ...commonValues,
        taskType:
          formValue.exportType === ImportExportFileType.SQL
            ? ImportExportTaskType.SQL_FILE_IMPORT
            : ImportExportTaskType.DATA_FILE_IMPORT,
        tableName,
        sourceFile: fileUrlList[0] || formValue.fileUrl || '',
        options,
      };
    },
  }));

  const handleFormChange = (changedValues, allValues) => {
    setFormValue({
      ...formValue,
      ...allValues,
    });
  };

  const handleSelectExportLocation = async () => {
    const fileName = await jcefApi?.selectDirectory();
    if (!fileName) return;
    setExportLocation(fileName);
  };

  return (
    <Form
      className={styles.form}
      layout="vertical"
      form={form}
      autoComplete="off"
      onValuesChange={handleFormChange}
      initialValues={formValue}
    >
      <Form.Item label={`${i18n('workspace.importExport.targetTable')}:`} name="tableNameDisplay">
        <Input autoComplete="off" disabled />
      </Form.Item>
      <Form.Item label={`${i18n('workspace.importExport.fileType')}:`} name="exportType">
        <Select options={isImport ? importTypeOptions : exportTypeOptions} />
      </Form.Item>
      {isExport && (
        <>
          <Form.Item label={`${i18n('workspace.importExport.compression')}:`} name="compression">
            <Select
              allowClear
              placeholder={i18n('workspace.importExport.off')}
              options={[
                { label: 'GZIP', value: 'GZIP' },
              ]}
            />
          </Form.Item>
          {checkpointableFormats.includes(formValue.exportType) && (
            <Form.Item label={`${i18n('workspace.importExport.checkpoint')}:`} name="checkpointRows">
              <Select
                allowClear
                placeholder={i18n('workspace.importExport.off')}
                options={[
                  { label: '10,000', value: 10000 },
                  { label: '100,000', value: 100000 },
                  { label: '1,000,000', value: 1000000 },
                ]}
              />
            </Form.Item>
          )}
        </>
      )}
      {isExport && isDesktop && (
        <Form.Item label={`${i18n('workspace.importExport.exportLocation')}:`} name="exportLocation">
          <div className={styles.exportLocationBox}>
            <Input autoComplete="off" disabled value={exportLocation} />
            <IconButton
              className={styles.iconButton}
              size={{ boxSize: 30, iconSize: 22, borderRadius: 6 }}
              code="icon-folder"
              onClick={handleSelectExportLocation}
            />
          </div>
        </Form.Item>
      )}
      {isImport && (
        <Form.Item>
          <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept={uploadLocalFileAccept} />
        </Form.Item>
      )}
      {isImport && formValue.exportType === ImportExportFileType.CSV && (
        <>
          <Form.Item label={`${i18n('workspace.importExport.charset')}:`} name="charset">
            <Input autoComplete="off" placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.delimiter')}:`} name="delimiter">
            <Input autoComplete="off" maxLength={1} placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.quoteChar')}:`} name="quoteChar">
            <Input autoComplete="off" maxLength={1} placeholder="&quot;" />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.skipRows')}:`} name="skipRows">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.nullString')}:`} name="nullString">
            <Input autoComplete="off" placeholder="\\N" />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.onError')}:`} name="onError">
            <Select
              allowClear
              placeholder="ABORT"
              options={[
                { label: 'ABORT', value: 'ABORT' },
                { label: 'SKIP', value: 'SKIP' },
              ]}
            />
          </Form.Item>
          {formValue.onError === 'SKIP' && (
            <Form.Item label={`${i18n('workspace.importExport.maxErrors')}:`} name="maxErrors">
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </>
      )}
      {isDevelopment && (
        <Form.Item label="File URL" name="fileUrl">
          <Input autoComplete="off" />
        </Form.Item>
      )}
      {/* <Form.Item name="containsHeader" valuePropName="checked">
        <Checkbox>{i18n('workspace.importExport.containsHeader')}</Checkbox>
      </Form.Item> */}
    </Form>
  );
});

export default memo(ImportExportFile);
