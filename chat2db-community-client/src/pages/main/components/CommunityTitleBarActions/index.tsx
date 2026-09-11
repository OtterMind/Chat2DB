import type { MouseEvent, ReactNode } from 'react';

import WorkspaceExtendNav from '../../workspace/components/WorkspaceExtend/WorkspaceExtendNav';
import { useStyles } from './style';

const stopDoubleClickPropagation = (event: MouseEvent<HTMLDivElement>) => {
  event.stopPropagation();
};

interface CommunityTitleBarActionsProps {
  extras?: ReactNode;
  showWorkspaceActions: boolean;
}

const CommunityTitleBarActions = ({ extras, showWorkspaceActions }: CommunityTitleBarActionsProps) => {
  const { styles } = useStyles();

  return (
    <div className={styles.toolbar}>
      {extras ? (
        <div className={styles.productActions} onDoubleClick={stopDoubleClickPropagation}>
          {extras}
        </div>
      ) : null}
      {showWorkspaceActions ? (
        <div className={styles.workspaceActions} onDoubleClick={stopDoubleClickPropagation}>
          <WorkspaceExtendNav orientation="horizontal" />
        </div>
      ) : null}
    </div>
  );
};

export default CommunityTitleBarActions;
