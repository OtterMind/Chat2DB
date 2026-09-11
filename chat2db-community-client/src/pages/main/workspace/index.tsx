import { memo } from 'react';
import SplitPane from 'react-split-pane';
import { useWorkspaceStore } from '@/store/workspace';
import WorkspaceLeft from './components/WorkspaceLeft';
import WorkspaceRight from './components/WorkspaceRight';

import { useStyles } from './style';

const workspacePage = memo(() => {
  const { cx, styles } = useStyles();
  const { panelLeftWidth, setPanelLeftWidth } = useWorkspaceStore((state) => {
    return {
      panelLeftWidth: state.layout.panelLeftWidth,
      setPanelLeftWidth: state.setPanelLeftWidth,
    };
  });
  return (
    <div className={styles.workspaceRoot} data-workspace-shortcut-surface="true">
      <SplitPane
        split="vertical"
        className={cx({ ['ResizerSizeIsZeroRight']: panelLeftWidth === 0 })}
        pane1Style={{ zIndex: 2 }}
        pane2Style={{ zIndex: 1 }}
        onDragFinished={(newSize) => {
          const nextWidth = newSize < 100 ? 0 : newSize;
          if (Number.isFinite(nextWidth) && nextWidth !== panelLeftWidth) {
            setPanelLeftWidth(nextWidth);
          }
        }}
        size={panelLeftWidth}
        minSize={0}
        primary="first"
      >
        <WorkspaceLeft />
        <WorkspaceRight />
      </SplitPane>
    </div>
  );
});

export default workspacePage;
