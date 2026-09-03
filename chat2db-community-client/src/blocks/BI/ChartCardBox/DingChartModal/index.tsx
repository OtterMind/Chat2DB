import { useState, forwardRef, useImperativeHandle, ForwardedRef } from 'react';
import { useStyles } from './style';
import { Modal, Select, Button, Input } from 'antd';
import { IChartItem, IDashboardItem } from '@/typings';
import { createChart, getDashboardById, updateDashboard, createDashboard } from '@/service/dashboard';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';
import { useDashboardStore } from '@/store/dashboard/store';
import { pinChartToDashboard } from './pinChartToDashboard';

interface IProps {
  className?: string;
}

export interface DingChartModalRef {
  openModal: (data: IChartItem) => void;
}

export default forwardRef((props: IProps, ref: ForwardedRef<DingChartModalRef>) => {
  const { styles } = useStyles();
  const { dashboardList, getDashboardList, setDashboardList, setCurrentDashboard } = useDashboardStore((state) => {
    return {
      dashboardList: state.dashboardList,
      getDashboardList: state.queryDashboardList,
      setDashboardList: state.setDashboardList,
      setCurrentDashboard: state.setCurrentDashboard,
    };
  });
  const [chartDetail, setChartDetail] = useState<IChartItem | null>();

  const [curDashboard, setCurDashboard] = useState<IDashboardItem | null>(null);
  const [newDashboardName, setNewDashboardName] = useState<string>('');
  const [createLoading, setCreateLoading] = useState<boolean>(false);
  const [submitLoading, setSubmitLoading] = useState<boolean>(false);

  const { refreshCurrentDashboard } = useDashboardStore((state) => ({
    refreshCurrentDashboard: state.refreshCurrentDashboard,
  }));

  useImperativeHandle(ref, () => ({
    openModal: (data) => {
      setChartDetail(data);
    },
  }));

  const options = dashboardList.map((item) => {
    return {
      label: item.name,
      value: item.id,
    };
  });

  const handleSubmit = () => {
    pinChartToDashboard(chartDetail, curDashboard, {
      createChart,
      getDashboardById,
      updateDashboard,
      refreshCurrentDashboard,
      closePinChartModal: () => setChartDetail(null),
      showPinSuccessMessage: () => staticMessage.success(i18n('dashboard.chart.pinToDashboardSuccess')),
      setSubmitLoading,
    });
  };

  const handleChange = (value) => {
    setCurDashboard(dashboardList.find((item) => item.id === value)!);
  };

  const handleCreateDashboard = () => {
    setCreateLoading(true);
    createDashboard({ name: newDashboardName })
      .then((res) => {
        getDashboardById({ id: res }).then((dashboardDetail) => {
          setDashboardList([dashboardDetail, ...dashboardList]);
          setCurDashboard(dashboardDetail);
          setCurrentDashboard(dashboardDetail);
        });
        setNewDashboardName('');
      })
      .finally(() => {
        setCreateLoading(false);
      });
  };

  return (
    <Modal
      open={!!chartDetail}
      destroyOnClose
      maskClosable={false}
      title={i18n('dashboard.chart.selectDashboard')}
      onCancel={() => {
        setChartDetail(null);
      }}
      onOk={handleSubmit}
      confirmLoading={submitLoading}
    >
      <Select
        value={curDashboard?.id}
        className={styles.select}
        onChange={handleChange}
        options={options}
        onClick={() => {
          getDashboardList();
        }}
        dropdownRender={(menu) => (
          <>
            {menu}
            <div className={styles.createDashboard}>
              <Input
                placeholder={i18n('dashboard.createName.placeholder')}
                value={newDashboardName}
                onChange={(e) => setNewDashboardName(e.target.value)}
              />
              <Button
                type="primary"
                disabled={!newDashboardName}
                loading={createLoading}
                onClick={handleCreateDashboard}
              >
                {i18n('dashboard.chart.createDashboard')}
              </Button>
            </div>
          </>
        )}
      />
    </Modal>
  );
});
