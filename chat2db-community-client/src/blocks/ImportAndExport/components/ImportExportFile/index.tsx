import { memo, useMemo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input, Select } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { IconButton } from '@chat2db/ui';
import { ImportExportType, ImportExportFileType } from '@/constants/importExport';
import { isDevelopment } from '@/utils/env';
import jcefApi from '@/jcef';

interface IProps {
  className?: string;
  setIsReady?: (p: boolean) => void;
  onImportFileChange?: (filePath: string) => void;
}

export interface ImportExportFileRef {
  getValues: () => any;
}

const exportTypeOptions = [
  { label: 'CSV', value: ImportExportFileType.CSV, accept: '.csv' },
  { label: 'XLSX', value: ImportExportFileType.XLSX, accept: '.xlsx' },
  { label: 'XLS', value: ImportExportFileType.XLS, accept: '.xls' },
  { label: 'SQL', value: ImportExportFileType.SQL, accept: '.sql' },
];

const ImportExportFile = forwardRef((props: IProps, ref: ForwardedRef<ImportExportFileRef>) => {
  const { setIsReady } = props;
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const [fileUrlList, setFileUrlList] = useState<string[]>([]);
  const [exportLocation, setExportLocation] = useState<string>('');
  const [formValue, setFormValue] = useState<any>({
    exportType: 'CSV',
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
      setIsReady && setIsReady(!!fileUrlList.length || formValue.fileUrl);
    }
  }, [fileUrlList, formValue]);

  useEffect(() => {
    if (isExport) {
      setIsReady && setIsReady(!!exportLocation || formValue.fileUrl);
    }
  }, [exportLocation, formValue]);

  const handleFileUrlListChange = (_fileUrlList) => {
    const paths = _fileUrlList.map((item) => item.filePath);
    setFileUrlList(paths);
    if (isImport && paths[0] && props.onImportFileChange) {
      props.onImportFileChange(paths[0]);
    }
  };

  useImperativeHandle(ref, () => ({
    getValues: () => {
      const { dataSourceId, databaseName, schemaName, tableName } = importExportDataBoundInfo || {};
      const values: any = {
        dataSourceId,
        databaseName,
        schemaName,
        containsHeader: formValue.containsHeader,
      };
      if (isExport) {
        values.tableNames = [tableName];
        values.exportPath = exportLocation || formValue.fileUrl;
        values.exportType = formValue.exportType;
      } else {
        values.tableName = tableName;
        values.fileName = fileUrlList[0] || formValue.fileUrl;
        values.importType = formValue.exportType;
      }
      return values;
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
      {isExport && (
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
