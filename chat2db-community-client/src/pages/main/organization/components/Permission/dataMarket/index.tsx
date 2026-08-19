import { useEffect, useMemo, useState } from 'react';
import i18n from '@/i18n';
import { Button, Flex, Table, Tag } from 'antd';
import dayjs from 'dayjs';
import dataMarketService from '@/service/dataMarket';

import { IPageParams } from '@/typings';
import { useStyles } from './style';
import { IDataSourceCollection } from '@/typings/dataMarket';
import DataMarketModal from './dataModal';
import SearchBar from '@/components/SearchBar';
import { ColumnsType } from 'antd/es/table';

const DataMarket = () => {
  const { styles } = useStyles();

  const [openModal, setOpenModal] = useState(false);
  const [currentDataItem, setCurrentDataItem] = useState<IDataSourceCollection>();
  const [dataSource, setDataSource] = useState<any[]>([]);
  const [pagination, setPagination] = useState({
    searchKey: '',
    current: 1,
    pageSize: 10,
    total: 0,
  });
  const columns: ColumnsType<any> = useMemo(
    () => [
      {
        title: '数据集名称',
        dataIndex: 'title',
        key: 'title',
        width: 300,
      },
      {
        title: '数据来源',
        dataIndex: 'collectionSource',
        key: 'collectionSource',
        render: (collectionSource) => {
          return <Tag color={collectionSource === 'DATA_SOURCE' ? 'blue' : 'green'}>{collectionSource}</Tag>;
        },
      },
      {
        title: '创建时间',
        dataIndex: 'createTime',
        key: 'createTime',
        render: (createTime) => <div>{dayjs(createTime).format('YYYY-MM-DD HH:mm:ss')}</div>,
      },
      {
        title: '更新时间',
        dataIndex: 'modifyTime',
        key: 'modifyTime',
        render: (modifyTime) => <div>{dayjs(modifyTime).format('YYYY-MM-DD HH:mm:ss')}</div>,
      },
      {
        title: '操作',
        dataIndex: 'action',
        key: 'action',
        render: (_, record) => {
          return (
            <Flex gap={8}>
              <Button
                type="link"
                onClick={() => {
                  handleQueryDetail(record.id);
                }}
              >
                编辑
              </Button>
            </Flex>
          );
        },
      },
    ],
    [],
  );

  useEffect(() => {
    queryDataMarketList();
  }, [pagination.searchKey, pagination.current, pagination.pageSize]);

  const handleQueryDetail = (id: number) => {
    dataMarketService.queryDataMarketDetail({ id }).then((res) => {
      setCurrentDataItem(res);
      setOpenModal(true);
    });
  };

  const queryDataMarketList = async () => {
    const pageParams: IPageParams = {
      ...pagination,
      pageNo: pagination.current,
    };

    const res = await dataMarketService.queryDataMarketList(pageParams);
    setDataSource(res.data || []);
    setPagination({
      ...pagination,
      total: res.total,
    });
  };

  const handleTableChange = (p) => {
    setPagination({
      ...pagination,
      ...p,
    });
  };

  return (
    <div className={styles.container}>
      <div className={styles.tableTop}>
        <SearchBar
          style={{ width: '320px' }}
          placeholder={i18n('common.text.searchPlaceholder')}
          onPressEnter={() => {
            console.log('onPressEnter');
          }}
        />
        <Button
          type="primary"
          onClick={() => {
            setOpenModal(true);
          }}
        >
          添加数据集合
        </Button>
      </div>
      <Table
        rowKey={'id'}
        dataSource={dataSource}
        columns={columns}
        pagination={pagination}
        onChange={handleTableChange}
      />
      <DataMarketModal
        openModal={openModal}
        setOpenModal={setOpenModal}
        dataItem={currentDataItem}
        onConfirm={() => {
          queryDataMarketList();
        }}
      />
    </div>
  );
};

export default DataMarket;
