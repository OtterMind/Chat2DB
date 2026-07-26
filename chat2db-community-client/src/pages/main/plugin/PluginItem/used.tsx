import { useEffect, useMemo, useRef, useState } from 'react';
import PluginService from '@/service/plugin';
import { IPluginDataPackageVO } from '@/typings/plugin';
import { Table, Tag } from 'antd';
import { IconButton } from '@chat2db/ui';
import { formatFileSize } from '@/utils/file';
import i18n from '@/i18n';
import { useStyles } from './style';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

const UsedContent = ({ token }) => {
  const { styles } = useStyles();
  const [pluginDataPackageList, setPluginDataPackageList] = useState<IPluginDataPackageVO[]>([]);
  const requestGenerationRef = useRef(0);
  useEffect(() => {
    if (token) {
      queryPluginDataPackageList(token);
    }
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const queryPluginDataPackageList = async (accessToken) => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    const res = await PluginService.queryPluginDataPackageList({ token: accessToken });
    if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
    setPluginDataPackageList(res);
  };

  const columns = useMemo(
    () => [
      {
        title: 'No.',
        dataIndex: 'id',
        key: 'id',
        render: (text, record, index) => index + 1,
      },
      {
        title: i18n('plugin.item.usage.buyTime'),
        dataIndex: 'buyTime',
        key: 'buyTime',
      },
      {
        title: i18n('plugin.item.usage.runOutTime'),
        dataIndex: 'runOutTime',
        key: 'runOutTime',
        render: (text) => {
          return text || '-';
        },
      },
      {
        title: i18n('plugin.item.usage.size'),
        dataIndex: 'size',
        key: 'size',
        render: (text) => formatFileSize(text),
      },
      {
        title: i18n('plugin.item.usage.used'),
        dataIndex: 'used',
        key: 'used',
        render: (text) => formatFileSize(text),
      },
      {
        title: i18n('plugin.item.usage.status'),
        dataIndex: 'status',
        key: 'status',
        render: (text) => {
          return (
            <Tag color={text === 'valid' ? 'success' : 'error'}>
              {text === 'valid' ? i18n('plugin.item.usage.status.valid') : i18n('plugin.item.usage.status.invalid')}
            </Tag>
          );
        },
      },
    ],
    [],
  );

  return (
    <>
      <IconButton code="icon-refresh" className={styles.refresh} onClick={() => queryPluginDataPackageList(token)} />
      <Table dataSource={pluginDataPackageList} columns={columns} />
    </>
  );
};

export default UsedContent;
