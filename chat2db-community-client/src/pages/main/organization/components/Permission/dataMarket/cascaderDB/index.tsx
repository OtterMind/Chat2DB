import { ReactNode, useEffect, useRef, useState } from 'react';
import connection from '@/service/connection';
import sqlService from '@/service/sql';
import { Cascader } from 'antd';
import Iconfont from '@/components/Iconfont';
import { databaseMap } from '@/constants/database';
import { useStyles } from './style';
import { IConnectionListItem } from '@/typings/connection';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

enum DBFieldType {
  'dataSource',
  'database',
  'schema',
  'table',
  'column',
}

export interface DBField {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  columnName?: string;
}

interface Option extends DBField {
  key: string;
  value: string | number;
  label: ReactNode;
  children?: Option[];
  disableCheckbox?: boolean;
  type?: DBFieldType;
}

interface CascaderDBProps {
  dataSourceList?: IConnectionListItem[] | null;
  onChange?: (value: any, selectedOptions: any) => void;
  value: any;
}

const CascaderDB = (props: CascaderDBProps) => {
  const [options, setOptions] = useState<Option[]>([]);
  const { styles } = useStyles();
  const requestGenerationRef = useRef(0);
  useEffect(() => {
    // TODO: Request data-source data.
    loadDataSource();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const loadDataSource = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    try {
      let dataSourceList = props.dataSourceList ?? [];
      if (dataSourceList.length === 0) {
        const res = await connection.getList({
          pageNo: 1,
          pageSize: 999,
          refresh: true,
        });
        dataSourceList = res?.data || [];
      }
      const formattedData = (dataSourceList || []).map((item) => ({
        key: `dataSource-${item.id}`,
        value: item.id,
        label: (
          <div className={styles.optionItem}>
            <Iconfont code={databaseMap[item.type]?.icon} />
            <span>{item.alias}</span>
          </div>
        ),
        type: DBFieldType.dataSource,
        isLeaf: false,
        dataSourceId: item.id,
      }));
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      setOptions(formattedData);
    } catch (error) {
      console.log(error);
    }
  };

  const loadDatabase = async (dataSource: Option) => {
    const { dataSourceId } = dataSource;
    try {
      const databaseList = await connection.getDatabaseList({
        dataSourceId: dataSourceId as number,
      });

      const formattedData = (databaseList || []).map((item) => ({
        ...item,
        key: `database-${item.name}`,
        value: item.name,
        label: (
          <div className={styles.optionItem}>
            <Iconfont code="&#xe744;" className={styles.optionItemIcon} />
            <span>{item.name}</span>
          </div>
        ),
        type: DBFieldType.database,
        isLeaf: false,
        dataSourceId,
        databaseName: item.name,
      }));
      dataSource.children = formattedData;
      setOptions([...options]);
    } catch (error) {
      console.log(error);
    }
  };

  const loadTable = async (dataBase: Option) => {
    const { dataSourceId, databaseName } = dataBase;
    try {
      const tableList = await sqlService.getTableList({
        dataSourceId,
        databaseName,
        pageNo: 1,
        pageSize: 100,
      });

      const formattedData = (tableList?.data || []).map((item) => ({
        key: `table-${item.name}`,
        value: item.name,
        label: (
          <div className={styles.optionItem}>
            <Iconfont code="&#xe618;" className={styles.optionItemIcon} />
            <span>{item.name}</span>
          </div>
        ),
        type: DBFieldType.table,
        isLeaf: true,
        dataSourceId,
        databaseName,
        tableName: item.name,
      }));
      dataBase.children = formattedData;
      setOptions([...options]);
    } catch (error) {
      console.log(error);
    }
  };

  const handleLoaderCascader = (selectedOptions) => {
    const targetOption = selectedOptions[selectedOptions.length - 1];

    if (targetOption.type === DBFieldType.dataSource) {
      loadDatabase(targetOption);
    }

    if (targetOption.type === DBFieldType.database) {
      loadTable(targetOption);
    }
  };

  const handleCascaderChange = (value, selectedOptions) => {
    // TODO: Require a table selection.
    props.onChange && props.onChange(value, selectedOptions);
  };

  return (
    <Cascader
      allowClear={false}
      className={styles.cascader}
      options={options}
      changeOnSelect
      placeholder="请选择数据源"
      loadData={handleLoaderCascader}
      onChange={handleCascaderChange}
      defaultValue={props.value}
    />
  );
};

export default CascaderDB;
