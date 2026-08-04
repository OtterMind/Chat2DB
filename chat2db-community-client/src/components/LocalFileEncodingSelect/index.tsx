import { useState } from 'react';
import { Select, Tooltip } from 'antd';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import { LOCAL_FILE_CHARSETS, formatLocalFileEncoding } from '@/utils/localFileEncoding';
import styles from './index.less';

const AUTO_DETECT_VALUE = '__auto_detect__';

interface LocalFileEncodingSelectProps {
  charset?: string;
  bom?: boolean;
  disabled?: boolean;
  onEncodingChange: (charset?: string) => Promise<void>;
}

const LocalFileEncodingSelect = ({
  charset,
  bom,
  disabled,
  onEncodingChange,
}: LocalFileEncodingSelectProps) => {
  const [loading, setLoading] = useState(false);
  const currentLabel = formatLocalFileEncoding(charset, bom);
  const charsetOptions: Array<{ value: string; label: string }> = LOCAL_FILE_CHARSETS.map((value) => ({
    value,
    label: value === charset ? currentLabel : value,
  }));
  if (charset && !LOCAL_FILE_CHARSETS.some((value) => value === charset)) {
    charsetOptions.unshift({ value: charset, label: currentLabel });
  }

  const handleChange = async (value: string) => {
    setLoading(true);
    try {
      await onEncodingChange(value === AUTO_DETECT_VALUE ? undefined : value);
    } catch (error) {
      console.error('reload local file with encoding error', error);
      staticMessage.error(i18n('workspace.fileEncoding.reloadFailed'));
    } finally {
      setLoading(false);
    }
  };

  const label = i18n('workspace.fileEncoding.label');
  return (
    <Tooltip title={label}>
      <span className={styles.wrapper}>
        <Select<string>
          className={styles.selector}
          size="small"
          variant="borderless"
          value={charset || AUTO_DETECT_VALUE}
          loading={loading}
          disabled={disabled || loading}
          aria-label={label}
          popupMatchSelectWidth={180}
          options={[
            { value: AUTO_DETECT_VALUE, label: i18n('workspace.fileEncoding.autoDetect') },
            ...charsetOptions,
          ]}
          onChange={handleChange}
        />
      </span>
    </Tooltip>
  );
};

export default LocalFileEncodingSelect;
