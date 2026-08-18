import type { MouseEvent } from 'react';

import WorkspaceExtendNav from '../../workspace/components/WorkspaceExtend/WorkspaceExtendNav';
import { useStyles } from './style';

const stopDoubleClickPropagation = (event: MouseEvent<HTMLDivElement>) => {
  event.stopPropagation();
};

const CommunityTitleBarActions = () => {
  const { styles } = useStyles();

  return (
    <div className={styles.toolbar}>
      <div className={styles.workspaceActions} onDoubleClick={stopDoubleClickPropagation}>
        <WorkspaceExtendNav orientation="horizontal" />
      </div>
    </div>
  );
};

export default CommunityTitleBarActions;
