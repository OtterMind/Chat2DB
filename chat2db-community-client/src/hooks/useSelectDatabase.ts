import { useState, useMemo, useEffect, useRef } from 'react';
import { normalizeTreeNodeLoadResult, treeConfig } from '@/blocks/NewTree/treeConfig';
import { DatabaseTypeCode } from '@/constants';
import { databaseMap } from '@/constants/database';
import { getDatabaseSupport } from '@/utils/database';
import { createSelectDatabaseRequestCoordinator } from './selectDatabaseRequest';

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
  const requestCoordinatorRef = useRef(createSelectDatabaseRequestCoordinator());

  useEffect(() => {
    requestCoordinatorRef.current.invalidate('database');
    requestCoordinatorRef.current.invalidate('schema');
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
    return () => {
      requestCoordinatorRef.current.invalidateAll();
    };
  }, []);

  const getDataSourceList = () => {
    requestCoordinatorRef.current.invalidate('database');
    requestCoordinatorRef.current.invalidate('schema');
    setDataSourceList(null);
    setDatabaseList([]);
    setSchemaList([]);
    void requestCoordinatorRef.current.run(
      'dataSource',
      () =>
        treeConfig['dataSources'].getChildren!({
          refresh: true,
        }),
      (res) => {
        const _dataSourceList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.dataSourceId!,
            label: item.originalTitle,
            databaseType: item.extraParams.databaseType!,
          };
        });
        setDataSourceList(_dataSourceList);
      },
      () => setDataSourceList([]),
    );
  };

  const getDatabaseList = (params: { dataSourceId: number; databaseType: DatabaseTypeCode }) => {
    setDatabaseList(null);
    setSchemaList([]);
    void requestCoordinatorRef.current.run(
      'database',
      () =>
        treeConfig['dataSource'].getChildren!({
          ...params,
          refresh: true,
        }),
      (res) => {
        const _databaseList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.databaseName!,
            label: item.originalTitle,
          };
        });
        setDatabaseList(_databaseList);
      },
      () => setDatabaseList([]),
    );
  };

  const getSchemaList = (params) => {
    setSchemaList(null);
    void requestCoordinatorRef.current.run(
      'schema',
      () =>
        treeConfig['database'].getChildren!({
          ...params,
          refresh: true,
        }),
      (res) => {
        const _schemaList = normalizeTreeNodeLoadResult(res).children.map((item) => {
          return {
            value: item.extraParams.schemaName!,
            label: item.originalTitle,
          };
        });

        setSchemaList(_schemaList);
      },
      () => setSchemaList([]),
    );
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
      requestCoordinatorRef.current.invalidate('database');
      requestCoordinatorRef.current.invalidate('schema');
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
          databaseType: dataSource.databaseType,
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
