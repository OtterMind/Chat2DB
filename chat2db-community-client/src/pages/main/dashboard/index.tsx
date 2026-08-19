import { memo, useEffect, useMemo } from 'react';
import { Splitter } from 'antd';
import { useStyles } from './style';
import DashboardContent from './DashboardContent';
import DashboardSetting from './DashboardSetting';
import { useDashboardStore } from '@/store/dashboard/store';
import { checkIsSharePage } from '@/utils/url';
import { useParams } from 'umi';
import AI from '@/blocks/AI';
import { useAIStore } from '@/store/ai';
import { useWorkspaceStore } from '@/store/workspace';

export default memo(() => {
  const { dashboardId: dashboardIdString } = useParams<{ dashboardId: string }>();
  const dashboardId = useMemo(() => (dashboardIdString ? Number(dashboardIdString) : undefined), [dashboardIdString]);
  const { styles } = useStyles();
  const { dashboardList, getDashboardById, queryDashboardList } = useDashboardStore((state) => ({
    dashboardList: state.dashboardList,
    getDashboardById: state.getDashboardById,
    queryDashboardList: state.queryDashboardList,
  }));
  const { showPanel: showAIPanel } = useAIStore((state) => ({
    showPanel: state.showPanel,
  }));

  // TODO: Reuse the Workspace Right width so the right panel remains stable while switching views.
  const { panelRight, panelRightWidth } = useWorkspaceStore((state) => {
    return {
      panelRight: state.layout.panelRight,
      panelRightWidth: state.layout.panelRightWidth,
    };
  });

  const isSharePage = useMemo(() => checkIsSharePage(), []);

  useEffect(() => {
    if (dashboardId) {
      getDashboardById(Number(dashboardId));
      return;
    }
    if (!dashboardList.length) {
      queryDashboardList();
    }
  }, [dashboardId, dashboardList.length, getDashboardById, queryDashboardList]);

  if (isSharePage) {
    return <DashboardContent isShare={isSharePage} />;
  }

  const showRightPanel = panelRight && showAIPanel;

  return (
    <>
      <Splitter className={styles.container}>
        <Splitter.Panel>
          <DashboardContent />
        </Splitter.Panel>
        {showRightPanel && (
          <Splitter.Panel defaultSize={panelRightWidth} min={150}>
            <AI variant="panel" />
          </Splitter.Panel>
        )}
      </Splitter>
      <DashboardSetting />
    </>
  );
});
