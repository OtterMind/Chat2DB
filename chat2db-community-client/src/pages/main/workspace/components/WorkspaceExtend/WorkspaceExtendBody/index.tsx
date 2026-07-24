import { useMemo } from 'react';
import { extendConfig } from '../config';
import { useWorkspaceStore } from '@/store/workspace';
import { useStyles } from './style';
import { useAIStore } from '@/store/ai';
import AI from '@/blocks/AI';
import {
  isWorkspaceResultInspectorCode,
  WORKSPACE_RESULT_INSPECTOR_PORTAL_ID,
} from '@/store/workspace/utils/resultInspector';

export default () => {
  const { styles } = useStyles();

  const currentWorkspaceExtend = useWorkspaceStore((state) => state.currentWorkspaceExtend);
  const showPanel = useAIStore((state) => state.showPanel);

  const Component = useMemo(() => {
    return extendConfig.find((item) => item.code === currentWorkspaceExtend)?.components;
  }, [currentWorkspaceExtend]);

  if (isWorkspaceResultInspectorCode(currentWorkspaceExtend)) {
    return (
      <div className={styles.currentWorkspaceExtendBox}>
        <div id={WORKSPACE_RESULT_INSPECTOR_PORTAL_ID} className={styles.resultInspectorHost} />
      </div>
    );
  }

  if (showPanel) {
    return (
      <div className={styles.currentWorkspaceExtendBox}>
        <AI variant="panel" />
      </div>
    );
  }

  return Component ? (
    <div className={styles.currentWorkspaceExtendBox}>
      <Component />
    </div>
  ) : (
    false
  );
};
