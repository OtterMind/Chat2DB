import {
  memo,
  useMemo,
  useState,
  useRef,
  forwardRef,
  ForwardedRef,
  useImperativeHandle,
  useEffect,
} from 'react';
import feedback from '@/utils/feedback';
import { useStyles } from './style';
import BaseTable, { BaseTableRef } from '@/components/BaseTable';
import { ToolbarBtn } from '@chat2db/ui';
import { Flex } from 'antd';
import { ActionType } from '@/constants/redis';
import { mapDisplayedIndexToValueListIndex } from './mapDisplayedIndex';
// import { v4 as uuid } from 'uuid';
import lodash from 'lodash';
import { StreamValue } from '@/typings/redis';
import { openAddColumnModal } from './addColumn';
import i18n from '@/i18n';

interface IProps {
  className?: string;
  originalStreamList?: StreamValue[];
}

export interface CreateStreamRef {
  getStreamList: () => StreamValue[];
}

const streamHeaderIdentifie = 'CHAT2DB_STREAM_HEADER_';

const CreateStream = forwardRef((props: IProps, ref: ForwardedRef<CreateStreamRef>) => {
  const { className, originalStreamList } = props;
  const { styles, cx } = useStyles();

  const [valueList, setValueList] = useState<any[]>([]);
  const [selectedRows, setSelectedRows] = useState<number[]>([]);
  const [initStreamItem, setInitStreamItem] = useState<any>({
    id: '*',
  });

  const [columns, setColumns] = useState<any[]>([
    {
      title: 'ID',
      name: `id`,
      editable: (data) => {
        return data.action === ActionType.ORIGINAL ? false : undefined;
      },
    },
  ]);
  const baseTableRef = useRef<BaseTableRef>(null);

  // generates a new column
  const generateNewColumn = (name) => {
    return {
      title: name,
      name: `${streamHeaderIdentifie}${name}`,
      editable: (data) => {
        return data.action === ActionType.ORIGINAL ? false : undefined;
      },
    };
  };

  useEffect(() => {
    if ((originalStreamList?.length || 0) === 0) {
      return;
    }
    // 1. Convert the source data into table rows.
    const newOriginalStreamList = lodash.cloneDeep(originalStreamList);
    const _defaultValueList =
      newOriginalStreamList?.map((iItem) => {
        iItem.values.forEach((jItem) => {
          iItem[`${streamHeaderIdentifie}${jItem.key}`] = jItem.value;
          iItem.action = ActionType.ORIGINAL;
        });
        return iItem;
      }) || [];

    // 2. Create an initial stream item for new rows.
    // 3. Build the columns.
    const _initStreamItem: any = {};
    const _columns = originalStreamList?.[0]?.values.map((item) => {
      _initStreamItem[item.key] = '';
      return generateNewColumn(item.key);
    });

    _initStreamItem.id = '*';
    _columns?.unshift({
      title: 'ID',
      name: `id`,
      editable: (data) => {
        return data.action === ActionType.ORIGINAL ? false : undefined;
      },
    });

    setValueList(_defaultValueList);
    setColumns(_columns || []);
    setInitStreamItem(_initStreamItem);
  }, [originalStreamList]);

  // Get the value list.
  const getStreamList = () => {
    // Convert valueList back to the source data format.
    const result = valueList.map((item) => {
      const values = Object.keys(item).reduce((prev: any, key) => {
        if (key.startsWith(streamHeaderIdentifie)) {
          prev.push({
            key: key.replace(streamHeaderIdentifie, ''),
            value: item[key],
          });
        }
        return prev;
      }, []);
      return {
        id: item.id,
        values,
        action: item.action,
      };
    });
    return result;
  };

  // Expose methods to the parent component.
  useImperativeHandle(ref, () => ({
    getStreamList,
  }));

  // Handle additions.
  const handleAdd = () => {
    setValueList((prevTableData) => {
      const newData = [...prevTableData];
      newData.push({
        ...initStreamItem,
        action: ActionType.ADD,
      });
      return newData;
    });
    setTimeout(() => {
      baseTableRef.current?.scrollToBottom();
    }, 0);
  };

  // Handle deletions.
  const handleDelete = () => {
    if (selectedRows.length === 0) {
      return;
    }
    // Mark the row as deleted without removing it yet.
    const newValues: any = [];
    // Removing it now would invalidate the indexes.
    let deleteCount = 0;
    valueList.forEach((item, index) => {
      if (item.action === ActionType.DELETE) {
        deleteCount = deleteCount + 1;
      }
      if (selectedRows.includes(index - deleteCount)) {
        if (item.action === ActionType.ADD) {
          return;
        }
        newValues.push({
          ...item,
          action: ActionType.DELETE,
        });
      } else {
        newValues.push(item);
      }
    });
    setSelectedRows([]);
    setValueList(newValues);
  };

  // Handle cell edits.
  const handleCellChange = (index, columnName, value) => {
    const realIndex = mapDisplayedIndexToValueListIndex(valueList, index);
    if (realIndex === -1) return;
    setValueList((prevTableData) => {
      const newData = [...prevTableData];
      newData[realIndex][columnName] = value;
      // Keep the marker unchanged when editing a newly added row.
      if (newData[realIndex].action === ActionType.ADD) {
        return newData;
      }
      newData[realIndex].action = ActionType.UPDATE;
      return newData;
    });
  };

  const openAddColumnCallback = (name: string) => {
    setColumns((prev) => {
      // Do not add a column that already exists.
      if (prev.some((item) => item.title === name)) {
        feedback.error(i18n('redis.tips.columnExist'));
        return prev;
      }
      return [...prev, generateNewColumn(name)];
    });
    setInitStreamItem((prev) => {
      return {
        ...prev,
        [name]: '',
      };
    });
    setTimeout(() => {
      baseTableRef.current?.scrollToRight();
    }, 0);
  };

  const handleAddColumn = () => {
    openAddColumnModal(openAddColumnCallback);
  };

  // Filter out deleted rows.
  const tableData = useMemo(() => {
    return valueList.filter((item) => item.action !== ActionType.DELETE);
  }, [valueList]);

  return (
    <div className={cx(styles.createList, className)}>
      <BaseTable
        ref={baseTableRef}
        className={styles.baseTable}
        tableData={tableData}
        columns={columns}
        showEmptyState={false}
        onChangeCell={handleCellChange}
        selectedRows={selectedRows}
        onSelectedRowsChange={setSelectedRows}
      />
      <Flex align="center" gap="4px" className={styles.operationLine}>
        <ToolbarBtn prefixIcon="icon-rows" text={i18n('redis.button.addRow')} size="sm" onClick={handleAdd} />
        <ToolbarBtn
          prefixIcon="icon-minus"
          text={i18n('redis.button.deleteRow')}
          className={cx({ [styles.disabledToolbarBtn]: selectedRows[0] === undefined })}
          onClick={handleDelete}
        />
        {/* Add a column. */}
        <ToolbarBtn
          prefixIcon="icon-columns"
          text={i18n('redis.button.addColumn')}
          size="sm"
          onClick={handleAddColumn}
        />
      </Flex>
    </div>
  );
});

export default memo(CreateStream);
