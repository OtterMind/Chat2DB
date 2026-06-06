import React, { memo, useCallback, useEffect, useRef } from 'react';
import classnames from 'classnames';
import { Tooltip } from 'antd';

import { useWorkspaceStore } from '@/pages/main/workspace/store';
import { setPanelLeft, setPanelLeftWidth } from '@/pages/main/workspace/store/config';

import DraggableContainer from '@/components/DraggableContainer';
import Iconfont from '@/components/Iconfont';
import WorkspaceLeft from './components/WorkspaceLeft';
import WorkspaceRight from './components/WorkspaceRight';

import i18n from '@/i18n';
import useMonacoTheme from '@/components/MonacoEditor/useMonacoTheme';
import shortcutKeyCreateConsole from './functions/shortcutKeyCreateConsole';

import styles from './index.less';

const COLLAPSE_PANEL_LEFT_MEDIA = '(max-width: 960px)';

const workspacePage = memo(() => {
  const draggableRef = useRef<any>();
  const isAutoCollapseCheckedRef = useRef(false);
  const { panelLeft, panelLeftWidth } = useWorkspaceStore((state) => {
    return {
      panelLeft: state.layout.panelLeft,
      panelLeftWidth: state.layout.panelLeftWidth,
    };
  });

  // 编辑器的主题
  useMonacoTheme();

  // 快捷键
  useEffect(() => {
    shortcutKeyCreateConsole();
  }, []);

  useEffect(() => {
    if (isAutoCollapseCheckedRef.current) {
      return;
    }
    isAutoCollapseCheckedRef.current = true;
    if (window.matchMedia(COLLAPSE_PANEL_LEFT_MEDIA).matches) {
      setPanelLeft(false);
    }
  }, []);

  const draggableContainerResize = useCallback((data: number) => {
    setPanelLeftWidth(data);
  }, []);

  return (
    <div className={styles.workspace}>
      <DraggableContainer className={styles.workspaceMain} onResize={draggableContainerResize}>
        <div
          ref={draggableRef}
          style={{ '--panel-left-width': `${panelLeftWidth}px` } as any}
          className={classnames({ [styles.hiddenPanelLeft]: !panelLeft }, styles.boxLeft)}
        >
          <WorkspaceLeft />
        </div>
        <WorkspaceRight />
      </DraggableContainer>
      {!panelLeft && (
        <Tooltip title={i18n('workspace.tree.expandDataSourceTree')} placement="right">
          <div className={styles.expandPanelLeft} onClick={() => setPanelLeft(true)}>
            <Iconfont code="&#xe670;" />
          </div>
        </Tooltip>
      )}
    </div>
  );
});

export default workspacePage;
