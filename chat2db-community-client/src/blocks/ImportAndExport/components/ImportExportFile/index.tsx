import { memo, useMemo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input, Select } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { IconButton } from '@chat2db/ui';
import { ImportExportType, ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import { ExportTaskParams, ImportTaskParams } from '@/service/importExport';
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
}

const exportTypeOptions = [
  { label: 'CSV', value: ImportExportFileType.CSV, accept: '.csv' },
  { label: 'XLSX', value: ImportExportFileType.XLSX, accept: '.xlsx' },
  { label: 'XLS', value: ImportExportFileType.XLS, accept: '.xls' },
  { label: 'JSON', value: ImportExportFileType.JSON, accept: '.json' },
  { label: 'SQL', value: ImportExportFileType.SQL, accept: '.sql' },
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
        };
      }
      return {
        ...commonValues,
        taskType:
          formValue.exportType === ImportExportFileType.SQL
            ? ImportExportTaskType.SQL_FILE_IMPORT
            : ImportExportTaskType.DATA_FILE_IMPORT,
        tableName,
        sourceFile: fileUrlList[0] || formValue.fileUrl || '',
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
        <Select options={exportTypeOptions} />
      </Form.Item>
      {isExport && isDesktop && (
        <Form.Item label={`${i18n('workspace.importExport.exportLocation')}:`} name="exportLocation">
          <div className={styles.exportLocationBox}>
            <Input autoComplete="off" disabled value={exportLocation} />
            <IconButton
              className={styles.iconButton}
              size={{ boxSize: 30, iconSize: 22, borderRadius: 6 } as any}
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
