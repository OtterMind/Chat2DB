import EditorChartModal, { EditChartModalRef } from '@/blocks/BI/ChartCardBox/EditorChartModal';
import { chartDetailNormalization } from '@/blocks/BI/utils/dataTreating';
import clientExtension from '@client-extension';
import i18n from '@/i18n';
import { createChart } from '@/service/dashboard';
import { useDashboardStore } from '@/store/dashboard/store';
import { appendLayoutItems } from '@/utils/dashboard';
import { EditText, Empty, EmptyImage, Icon, IconButton } from '@chat2db/ui';
import { useFullscreen } from 'ahooks';
import { Flex, Popover } from 'antd';
import { ChevronDown, LayoutDashboard, Plus } from 'lucide-react';
import { memo, useMemo, useRef, useState } from 'react';
import ChartCardList, { ChartCardListRef } from '../ChartCardList';
import DashboardMenuList from '../DashboardMenuList';
import { useStyles } from './style';

interface IProps {
  isShare?: boolean;
}

export default memo<IProps>((props) => {
  const { isShare = false } = props;
  const { currentDashboard, updateDashboard, setCurrentDashboard, setSettingDashboard, refreshCurrentDashboard } =
    useDashboardStore((state) => ({
      currentDashboard: state.currentDashboard,
      updateDashboard: state.updateDashboard,
      setCurrentDashboard: state.setCurrentDashboard,
      setSettingDashboard: state.setSettingDashboard,
      refreshCurrentDashboard: state.refreshCurrentDashboard,
    }));

  const createDashboardRef = useRef<EditChartModalRef>(null);
  const chartCardListRef = useRef<ChartCardListRef>(null);
  const [refreshLoading, setRefreshLoading] = useState(false);
  const [dashboardMenuOpen, setDashboardMenuOpen] = useState(false);
  const draggableModalAcceptPlace = useRef<HTMLDivElement>(null);

  const { styles } = useStyles();
  const dashboardContentRef = useRef<HTMLDivElement>(null);
  const [isFullscreen, { enterFullscreen, exitFullscreen }] = useFullscreen(dashboardContentRef);

  // All subsequent edit operations use this value to check permission.
  const isEditPermission = useMemo(() => {
    return !isShare && !isFullscreen;
  }, [isShare, isFullscreen]);

  // Edit the title.
  const editDashboardTitle = (text) => {
    if (!currentDashboard?.id) return;
    updateDashboard({ id: currentDashboard.id, name: text });
  };

  // Open the create-dashboard dialog.
  const openCreateDashboardModal = () => {
    createDashboardRef.current?.controlEditChartModal('editChart');
  };

  // Create a chart.
  const handleCreateChart = (data) => {
    if (!currentDashboard?.id) return;
    createChart(chartDetailNormalization(data)).then((chartId) => {
      const newDashboardDetail = {
        ...currentDashboard,
        schema: appendLayoutItems([chartId], {
          chartIds: currentDashboard?.chartIds || [],
          schema: currentDashboard?.schema || '',
        }),
        chartIds: [...(currentDashboard?.chartIds || []), chartId],
      };
      if (newDashboardDetail?.id) {
        updateDashboard(newDashboardDetail);
      }
      setCurrentDashboard(newDashboardDetail);
      chartCardListRef.current?.setActiveChart(chartId);
      createDashboardRef.current?.controlEditChartModal(false);
    });
  };

  const refreshChartList = () => {
    if (currentDashboard?.id) {
      setRefreshLoading(true);
      refreshCurrentDashboard().then(() => {
        setRefreshLoading(false);
      });
    }
  };

  const openSettingModal = () => {
    if (!currentDashboard?.id) return;
    setSettingDashboard(currentDashboard);
  };

  const dashboardActions =
    isEditPermission && currentDashboard?.id
      ? clientExtension.dashboardActions?.({ dashboardId: currentDashboard.id })
      : null;

  if (!currentDashboard) {
    return (
      <Flex justify="center" align="center" style={{ height: '100%' }}>
        <Empty
          image={EmptyImage.ChartList}
          title={i18n('dashboard.createDashboard.tip')}
          buttonText={i18n('dashboard.editor.createDashboard')}
          onButtonClick={() => {
            setSettingDashboard({});
          }}
        />
      </Flex>
    );
  }

  return (
    <>
      <div ref={dashboardContentRef} className={styles.container}>
        <div className={styles.containerHeader}>
          <div className={styles.headerLeft}>
            <div className={styles.headerTitle}>
              <Icon className={styles.headerTitleIcon} icon={LayoutDashboard} size="xl" />
              <EditText
                className={styles.headerTitleText}
                hoverShowBorder
                disabledEdit={!isEditPermission}
                onBlur={editDashboardTitle}
              >
                {currentDashboard?.name || ''}
              </EditText>
              <Popover
                content={
                  <div className={styles.dashboardMenu}>
                    <DashboardMenuList onSelect={() => setDashboardMenuOpen(false)} />
                  </div>
                }
                placement="bottomLeft"
                trigger="click"
                open={dashboardMenuOpen}
                onOpenChange={setDashboardMenuOpen}
              >
                <span className={styles.dashboardMenuTrigger}>
                  <IconButton
                    icon={ChevronDown}
                    isActive={dashboardMenuOpen}
                    size={{ boxSize: 24, iconSize: 16 }}
                    title={i18n('dashboard.title')}
                  />
                </span>
              </Popover>
            </div>
            {isEditPermission && (
              <>
                <div className={styles.createDashboardButton} onClick={openCreateDashboardModal}>
                  <Icon icon={Plus} />
                  {i18n('dashboard.editor.createChart')}
                </div>
              </>
            )}
          </div>
          <div className={styles.headerRight}>
            {isEditPermission && (
              <IconButton
                code="icon-refresh"
                title={i18n('common.button.refresh')}
                size="md"
                spin={refreshLoading}
                onClick={refreshChartList}
              />
            )}
            {isEditPermission && (
              <IconButton
                code="icon-setting"
                title={i18n('common.text.setting')}
                size="md"
                onClick={openSettingModal}
              />
            )}
            {dashboardActions}
            {isFullscreen ? (
              <IconButton
                code="icon-exit-full-screen"
                title={i18n('dashboard.title.exitDemoMode')}
                size="md"
                onClick={exitFullscreen}
              />
            ) : (
              <IconButton
                code="icon-enter-full-screen"
                title={i18n('dashboard.title.enterDemoMode')}
                size="md"
                onClick={enterFullscreen}
              />
            )}
          </div>
        </div>
        <div className={styles.containerBody}>
          {!!currentDashboard && (
            <ChartCardList
              isEditPermission={isEditPermission}
              openCreateDashboardModal={openCreateDashboardModal}
              ref={chartCardListRef}
            />
          )}
          <div className={styles.draggableModalAcceptPlace} ref={draggableModalAcceptPlace} />
        </div>
      </div>
      <EditorChartModal submitEditorChartCallback={handleCreateChart} ref={createDashboardRef} />
    </>
  );
});
