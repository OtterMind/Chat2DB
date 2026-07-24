import { Fragment, memo, useLayoutEffect, useState, useRef } from 'react';
import WorkspaceExtendBody from '../WorkspaceExtend/WorkspaceExtendBody';
import WorkspaceExtendNav from '../WorkspaceExtend/WorkspaceExtendNav';
import { useWorkspaceStore } from '@/store/workspace';
import {
  getResultInspectorPanelSize,
  isWorkspaceResultInspectorCode,
  RESULT_INSPECTOR_MAX_PANEL_RATIO,
} from '@/store/workspace/utils/resultInspector';
import SplitPane from 'react-split-pane';
import ExportProgressBar from '@/blocks/ImportAndExport/components/ExportProgressBar';
import { canImportExport } from '@/utils/env';
// import DragFileToApp from '@/components/DragFileToApp';

// ----- components -----
import WorkspaceTabs from '../WorkspaceTabs';
import { useStyles } from './style';

const WorkspaceRight = memo(() => {
  const [workspaceWidth, setWorkspaceWidth] = useState(0);
  const draggablePanelRef = useRef<HTMLDivElement>(null);

  const { styles } = useStyles();

  const { currentWorkspaceExtend, panelRight, panelRightWidth, setPanelRightWidth } = useWorkspaceStore((state) => {
    return {
      currentWorkspaceExtend: state.currentWorkspaceExtend,
      panelRight: state.layout.panelRight,
      panelRightWidth: state.layout.panelRightWidth,
      setPanelRightWidth: state.setPanelRightWidth,
    };
  });

  useLayoutEffect(() => {
    if (!draggablePanelRef.current) return;

    setWorkspaceWidth(draggablePanelRef.current.getBoundingClientRect().width);
    const resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setWorkspaceWidth(entry.contentRect.width);
      }
    });

    resizeObserver.observe(draggablePanelRef.current);
    return () => resizeObserver.disconnect();
  }, []);

  const resultInspectorOpen = isWorkspaceResultInspectorCode(currentWorkspaceExtend);
  const preferredPanelSize = panelRightWidth || 320;
  const size = panelRight
    ? resultInspectorOpen
      ? getResultInspectorPanelSize(preferredPanelSize, workspaceWidth)
      : preferredPanelSize
    : 0;
  const maxPanelSize = workspaceWidth * (resultInspectorOpen ? RESULT_INSPECTOR_MAX_PANEL_RATIO : 0.8);

  return (
    <div className={styles.workspaceRight}>
      <div className={styles.draggablePanel} ref={draggablePanelRef}>
        <SplitPane
          split="vertical"
          size={size}
          pane1Style={{ minWidth: '0px' }}
          minSize={150}
          maxSize={maxPanelSize}
          primary="second"
          allowResize={panelRight}
          onChange={(newSize) => {
            setPanelRightWidth(newSize);
          }}
        >
          {/* <DragFileToApp className={styles.masterScope} onDropCallback={handleDropCallback} fileType="sql">
            <div className={styles.masterScopeMain}>
              <WorkspaceTabs />
            </div>
            <div className={styles.masterScopeBottom}>{canImportExport && <ExportProgressBar />}</div>
          </DragFileToApp> */}
          <div className={styles.masterScope}>
            <div className={styles.masterScopeMain}>
              <WorkspaceTabs />
            </div>
            <div className={styles.masterScopeBottom}>{canImportExport && <ExportProgressBar />}</div>
          </div>
          <Fragment>{panelRight && <WorkspaceExtendBody />}</Fragment>
        </SplitPane>
      </div>
      <WorkspaceExtendNav />
    </div>
  );
});

export default WorkspaceRight;
