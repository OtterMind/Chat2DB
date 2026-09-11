import { memo, useEffect, useState } from 'react';
import i18n from '@/i18n';
import { Form, Input } from 'antd';
import { DatabaseTypeCode } from '@/constants';
import UploadLocalFile from '@/components/UploadLocalFile';
import { isDesktop } from '@/utils/env';

interface IProps {
  className?: string;
  databaseType: DatabaseTypeCode;
  formChange: (data: any) => void;
  jdbcDriverClass: string | undefined;
}

export default memo<IProps>((props) => {
  const { databaseType = DatabaseTypeCode.MYSQL, formChange, jdbcDriverClass } = props;
  const [formData, setFormData] = useState<any>({
    dbType: databaseType,
    jdbcDriverClass: jdbcDriverClass,
    jdbcDriver: [],
  });

  useEffect(() => {
    formChange(formData);
  }, [formData]);

  function onChange(e: any) {
    setFormData({
      ...formData,
      jdbcDriverClass: e.target.value,
    });
  }

  const handleFileUrlListChange = (fileUrlList: any[]) => {
    const selectedFile = fileUrlList?.[0];
    setFormData({
      ...formData,
      driverFiles: !isDesktop && selectedFile?.file ? [selectedFile.file] : [],
      jdbcDriver: selectedFile?.filePath ? [selectedFile.filePath] : [],
    });
  };

  return (
    <Form layout="vertical">
      <Form.Item label="Class">
        <Input value={formData.jdbcDriverClass} onChange={onChange} />
      </Form.Item>
      <Form.Item label={i18n('connection.title.uploadDriver')}>
        <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept={'.jar'} />
      </Form.Item>
    </Form>
  );
});
