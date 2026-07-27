import { memo, useMemo } from 'react';
import { Dropdown, MenuProps, Space } from 'antd';
import { ChevronDown } from 'lucide-react';
import { ExportSizeEnum, ExportTypeEnum } from '@/typings/resultTable';
import i18n from '@/i18n';
import sqlService, { IExportParams } from '@/service/sql';
import { downloadFile } from '@/utils/file';
import { useStyles } from './style';
import { isDesktop } from '@/utils/env';
import { IManageResultData } from '@/typings';
import jcefApi from '@/jcef';

interface IProps {
  resultData: IManageResultData;
}

export default memo<IProps>((props) => {
  const { resultData } = props;
  const { executeSqlParams } = resultData;
  const { styles } = useStyles();
  const handleExportSQLResult = async (exportType: ExportTypeEnum, exportSize: ExportSizeEnum) => {
    const params: IExportParams = {
      ...(executeSqlParams || {}),
      sql: resultData.sql,
      originalSql: resultData.originalSql,
      exportType,
      exportSize,
    };
    if (isDesktop) {
      sqlService.exportResultTable(params).then((res) => {
        jcefApi?.revealInExplorer(res);
      });
    } else {
      downloadFile('/api/rdb/dml/export', params);
    }
  };
  // export sql menu item
  const exportDropdownItems: MenuProps['items'] = useMemo(
    () => [
      {
        label: i18n('workspace.table.export.all.xlsx'),
        key: '0',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.EXCEL, ExportSizeEnum.ALL);
        },
      },
      {
        label: i18n('workspace.table.export.all.csv'),
        key: '1',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.CSV, ExportSizeEnum.ALL);
        },
      },
      {
        label: i18n('workspace.table.export.all.insert'),
        key: '2',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.INSERT, ExportSizeEnum.ALL);
        },
      },
      {
        label: i18n('workspace.table.export.cur.xlsx'),
        key: '3',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.EXCEL, ExportSizeEnum.CURRENT_PAGE);
        },
      },
      {
        label: i18n('workspace.table.export.cur.csv'),
        key: '4',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.CSV, ExportSizeEnum.CURRENT_PAGE);
        },
      },
      {
        label: i18n('workspace.table.export.cur.insert'),
        key: '5',
        // icon: <UserOutlined />,
        onClick: () => {
          handleExportSQLResult(ExportTypeEnum.INSERT, ExportSizeEnum.CURRENT_PAGE);
        },
      },
    ],
    [resultData],
  );
  return (
    <Dropdown destroyPopupOnHide menu={{ items: exportDropdownItems }} trigger={['click']}>
      <Space className={styles.exportBar}>
        {i18n('common.text.export')}
        <ChevronDown size={14} />
      </Space>
    </Dropdown>
  );
});
