import { FC, useEffect, useState } from 'react';
import { useStyles } from './style';
import { EmptyImage, IconButton, Empty } from '@chat2db/ui';
import i18n from '@/i18n';
import { SquarePlus, Trash2 } from 'lucide-react';
import { useDashboardStore } from '@/store/dashboard/store';
import InfiniteScroll from 'react-infinite-scroll-component';
import { Skeleton } from 'antd';
import { useGlobalStore } from '@/store/global';

const scrollId = 'chartMenuListDiv';

interface DashboardMenuListProps {
  dashboardId?: number;
  onSelect?: () => void;
}

const DashboardMenuList: FC<DashboardMenuListProps> = (props) => {
  const { dashboardId, onSelect } = props;
  const { styles, cx } = useStyles();

  const {
    currentDashboard,
    dashboardList,
    dashboardListParams,
    deleteDashboard,
    queryDashboardList,
    setSettingDashboard,
    getDashboardById,
  } = useDashboardStore((state) => {
    return {
      currentDashboard: state.currentDashboard,
      dashboardList: state.dashboardList,
      dashboardListParams: state.dashboardListParams,
      deleteDashboard: state.deleteDashboard,
      queryDashboardList: state.queryDashboardList,
      setSettingDashboard: state.setSettingDashboard,
      getDashboardById: state.getDashboardById,
    };
  });

  const { openUnifiedConfirmationModal } = useGlobalStore((state) => {
    return {
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
    };
  });

  // Optimistically highlight the item immediately without waiting for the API.
  const [selectedId, setSelectedId] = useState<number | undefined>(currentDashboard?.id);

  useEffect(() => {
    setSelectedId(currentDashboard?.id);
  }, [currentDashboard?.id]);

  useEffect(() => {
    if (!dashboardList.length) {
      queryDashboardList(dashboardId ? Number(dashboardId) : undefined);
    }
  }, []);

  const handleCreateNewDashboard = () => {
    setSettingDashboard({});
    onSelect?.();
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div>{i18n('dashboard.title')}</div>
        <IconButton
          size={{
            boxSize: 24,
            iconSize: 16,
          }}
          onClick={handleCreateNewDashboard}
          icon={SquarePlus}
        />
      </div>
      {dashboardList.length ? (
        <div className={styles.flowWrapper} id={scrollId}>
          <InfiniteScroll
            scrollableTarget={scrollId}
            dataLength={dashboardList.length}
            next={queryDashboardList}
            hasMore={!!dashboardListParams.hasNextPage}
            scrollThreshold={'50px'}
            loader={
              <Skeleton
                active
                paragraph={{ width: '80px', rows: 3 }}
                style={{ paddingLeft: '12px', paddingTop: '8px' }}
              />
            }
          >
            {(dashboardList || []).map((t) => {
              return (
                <div
                  key={t.id}
                  className={cx(
                    styles.dashboardItem,
                    t.id === selectedId && styles.dashboardItemActive,
                  )}
                  onClick={() => {
                    if (t.id != null && t.id !== selectedId) {
                      setSelectedId(t.id);
                      getDashboardById(t.id);
                    }
                    onSelect?.();
                  }}
                >
                  <div className={styles.dashboardItemTitle}>{t.name}</div>
                  <div
                    className={cx(styles.dashboardItemDelete, 'dashboard-item-delete')}
                    onClick={(e) => {
                      e.stopPropagation();
                      openUnifiedConfirmationModal({
                        title: i18n('common.text.deleteConfirmTitle'),
                        content: i18n('dashboard.delete.confirm'),
                        onOk: () => {
                          return deleteDashboard(t.id!);
                        },
                      });
                    }}
                  >
                    <Trash2 size={14} />
                  </div>
                </div>
              );
            })}
          </InfiniteScroll>
        </div>
      ) : (
        <Empty className={styles.empty} image={EmptyImage.ChartList} title={'暂无数据'} />
      )}
    </div>
  );
};

export default DashboardMenuList;
