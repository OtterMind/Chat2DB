import AIButton from '@/blocks/AI/components/AIButton';
import { canShareDashboard } from '@/edition-ui/runtimeCapabilities';
import useRuntimeEditionCapabilities from '@/hooks/useRuntimeEditionCapabilities';
import i18n from '@/i18n';
import { useAIStore } from '@/store/ai';
import { useGlobalStore } from '@/store/global';
import { useUserStore } from '@/store/user';
import { copyToClipboard } from '@/utils';
import { IconButton, staticMessage } from '@chat2db/ui';
import { memo } from 'react';

interface IProps {
  dashboardId?: number | string;
}

export default memo<IProps>((props) => {
  const { dashboardId } = props;
  const { appUrlConfig } = useGlobalStore((state) => ({
    appUrlConfig: state.appUrlConfig,
  }));

  const { curUser } = useUserStore((state) => ({
    curUser: state.curUser,
  }));
  const capabilities = useRuntimeEditionCapabilities();

  const handleShare = () => {
    if (!dashboardId) return;
    staticMessage.success(i18n('dashboard.share.linkCopied'));
    copyToClipboard(`${appUrlConfig.CHAT2DB_APP_URL}/dashboard/share/${dashboardId}`);
  };

  return (
    <>
      {canShareDashboard(capabilities, curUser?.currentOrganization?.type) && (
        <IconButton code="icon-share" title="share" size="md" onClick={handleShare} />
      )}
      {capabilities.dashboardHostedAiGenerate && (
        <AIButton
          size="md"
          onClick={() => {
            useAIStore.getState().togglePanel();
          }}
        />
      )}
    </>
  );
});
