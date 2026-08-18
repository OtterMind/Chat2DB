import React, { memo } from 'react';
import styles from './index.less';
import classnames from 'classnames';
import { useWorkspaceStore } from '@/store/workspace';
import { useAIStore } from '@/store/ai';
import { COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';
import { IconButton } from '@chat2db/ui';
import { PanelLeft, PanelRight } from 'lucide-react';

interface IProps {
  className?: string;
}

export default memo<IProps>((props) => {
  const { className } = props;
  const { panelRight, currentWorkspaceExtend, togglePanelRight, togglePanelLeft } = useWorkspaceStore(
    (state) => {
      return {
        panelRight: state.layout.panelRight,
        currentWorkspaceExtend: state.currentWorkspaceExtend,
        togglePanelLeft: state.togglePanelLeft,
        togglePanelRight: state.togglePanelRight,
      };
    },
  );
  const showAIPanel = useAIStore((state) => state.showPanel);

  // Stop event propagation.
  const stopPropagation = (e: React.MouseEvent<HTMLDivElement, MouseEvent>) => {
    e.stopPropagation();
  };

  const handleTogglePanelRight = () => {
    if (panelRight) {
      togglePanelRight(false);
      return;
    }

    if (!currentWorkspaceExtend && !showAIPanel) {
      useAIStore.getState().setShowPanel(true);
    }
    togglePanelRight(true);
  };

  return (
    <div className={classnames(styles.customLayout, className)}>
      <div onDoubleClick={stopPropagation}>
        <IconButton
          size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
          icon={PanelLeft}
          onClick={() => togglePanelLeft()}
        />
      </div>
      <div onDoubleClick={stopPropagation}>
        <IconButton
          size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
          icon={PanelRight}
          onClick={handleTogglePanelRight}
        />
      </div>
    </div>
  );
});
