import { useState, useMemo, useEffect } from 'react';
import { normalizeTreeNodeLoadResult, treeConfig } from '@/blocks/NewTree/treeConfig';
import { DatabaseTypeCode } from '@/constants';
import { databaseMap } from '@/constants/database';
import { getDatabaseSupport } from '@/utils/database';

export type ISelectDatabase = {
  dataSourceId?: number;
  databaseType?: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  supportSchema?: boolean;
  supportDatabase?: boolean;
  selectDone?: boolean;
} | null;

type IChangedValues = {
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
};

interface IUseSelectDatabaseProps {
  astrictDatabaseType?: DatabaseTypeCode;
}

const useSelectDatabase = (props: IUseSelectDatabaseProps) => {
  const { astrictDatabaseType } = props;
  const [dataSourceList, setDataSourceList] = useState<
    | {
        value: number;
        label: string;
        databaseType: DatabaseTypeCode;
      }[]
    | null
    >([]);
  
  const [databaseList, setDatabaseList] = useState<
    | {
        value: string;
        label: string;
      }[]
    | null
    >([]);
  
  const [schemaList, setSchemaList] = useState<
    | {
        value: string;
        label: string;
      }[]
    | null
  >([]);

  const [selectDatabase, setSelectDatabase] = useState<ISelectDatabase>();

  useEffect(() => {
    setDatabaseList([]);
    setSchemaList([]);

    if (astrictDatabaseType) {
      const { supportSchema, supportDatabase } = databaseMap[astrictDatabaseType];
      setSelectDatabase({
        dataSourceId: undefined,
        databaseName: undefined,
        schemaName: undefined,
        databaseType: undefined,
        supportSchema,
        supportDatabase,
      });
      return;
    }
    setSelectDatabase(null);
  }, [astrictDatabaseType]);

  const astrictDataSourceList = useMemo(() => {
    if (astrictDatabaseType) {
      return dataSourceList?.filter((item) => item.databaseType === astrictDatabaseType);
    }
    return dataSourceList;
  }, [dataSourceList, astrictDatabaseType]);

  useEffect(() => {
    getDataSourceList();
  }, []);

  const getDataSourceList = () => {
    setDataSourceList(null);
    setDatabaseList([]);
    setSchemaList([]);
    treeConfig['dataSources']
      .getChildren?.({
        refresh: true,
      })
      .then((res) => {
        const _dataSourceList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.dataSourceId!,
            label: item.originalTitle,
            databaseType: item.extraParams.databaseType!,
          };
        });
        setDataSourceList(_dataSourceList);
      })
      .catch(() => {
        setDataSourceList([]);
      });
  };

  const getDatabaseList = (params: { dataSourceId: number; databaseType: DatabaseTypeCode }) => {
    setDatabaseList(null);
    setSchemaList([]);
    treeConfig['dataSource']
      .getChildren?.({
        ...params,
        needAiDataCollections: false,
        refresh: true,
      })
      .then((res) => {
        const _databaseList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.databaseName!,
            label: item.originalTitle,
          };
        });
        setDatabaseList(_databaseList);
      })
      .catch(() => {
        setDatabaseList([]);
      });
  };

  const getSchemaList = (params) => {
    setSchemaList(null);
    treeConfig['database']
      .getChildren?.({
        ...params,
        needAiDataCollections: false,
        refresh: true,
      })
      .then((res) => {
        const _schemaList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.schemaName!,
            label: item.originalTitle,
          };
        });

        setSchemaList(_schemaList);
      })
      .catch(() => {
        setSchemaList([]);
      });
  };

  const isSelectDone = (params: ISelectDatabase) => {
    let flag = true;
    if (params?.supportDatabase) {
      if (!params.databaseName) {
        flag = false;
      }
    }

    if (params?.supportSchema) {
      if (!params.schemaName) {
        flag = false;
      }
    }

    return flag;
  };

  const onChangeSelectDatabase = (changedValues: IChangedValues) => {
    let newSelectDatabase: any = {
      ...selectDatabase,
    };

    if ('dataSourceId' in changedValues) {
      const dataSource = astrictDataSourceList?.find((item) => item.value === changedValues?.dataSourceId);

      if (!dataSource) {
        return;
      }

      const databaseType = dataSource.databaseType;

      const { supportSchema, supportDatabase } = getDatabaseSupport(databaseType);

      newSelectDatabase = {
        dataSourceId: dataSource.value,
        databaseName: undefined,
        schemaName: undefined,
        selectDone: false,
        databaseType,
        supportSchema,
        supportDatabase,
      };

      if (supportDatabase) {
        getDatabaseList({
          dataSourceId: dataSource.value,
          databaseType: dataSource.databaseType,
        });
      } else {
        getSchemaList({
          dataSourceId: dataSource.value,
          databaseType: dataSource.databaseType
        });
      }

    }
    
    if ('databaseName' in changedValues) {
      newSelectDatabase = {
        ...newSelectDatabase,
        schemaName: undefined,
        databaseName: changedValues.databaseName,
      };
      // Do you choose to complete
      if (isSelectDone(newSelectDatabase)) {
        newSelectDatabase.selectDone = true;
      }
      getSchemaList(newSelectDatabase);
    }
    
    if ('schemaName' in changedValues) {
      newSelectDatabase = {
        ...newSelectDatabase,
        schemaName: changedValues.schemaName,
      };
      // Do you choose to complete
      if (isSelectDone(newSelectDatabase)) {
        newSelectDatabase.selectDone = true;
      }
    }
    setSelectDatabase(newSelectDatabase);
  };

  return { dataSourceList: astrictDataSourceList, databaseList, schemaList, selectDatabase, onChangeSelectDatabase };
};

export default useSelectDatabase;
