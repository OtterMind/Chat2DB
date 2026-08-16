import { FC, useMemo } from 'react';
import AIChatHeader, { AIChatHeaderProps } from '.';
import { Dropdown, Flex } from 'antd';
import { IconButton } from '@chat2db/ui';
import { useAIStore } from '@/store/ai';
import { useChatStore } from '@/store/chat';
import { ChatVO } from '@/typings/chat';
import { useGlobalStore } from '@/store/global';
export interface AIChatHeaderInPanelProps extends AIChatHeaderProps {}

export const AIChatHeaderInPanel: FC<AIChatHeaderInPanelProps> = () => {
  const { setShowPanel } = useAIStore((state) => ({
    setShowPanel: state.setShowPanel,
  }));

  const { chatList, currentChat, setCurrentChat, createFakeNewChat } = useChatStore((state) => ({
    chatList: state.chatList,
    currentChat: state.currentChat,
    setCurrentChat: state.setCurrentChat,
    createFakeNewChat: state.createFakeNewChat,
  }));

  const { mainPageActiveTab } = useGlobalStore((state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
  }));

  const closeAIPlugin = () => {
    setShowPanel(false);
  };

  const createChat = () => {
    createFakeNewChat();
  };

  const extraBtn = useMemo(() => {
    return (
      <Flex gap={6} align="center">
        <IconButton code="icon-add" size={'md'} onClick={createChat} />
        <Dropdown
          trigger={['click']}
          menu={{
            items: (chatList || []).map((item: ChatVO) => ({
              key: item.id || '',
              label: item.title || '',
              onClick: () => {
                setCurrentChat({
                  ...currentChat,
                  [mainPageActiveTab]: item,
                });
              },
            })),
            selectedKeys: [String(currentChat?.[mainPageActiveTab]?.id) || ''],
          }}
          placement="bottomLeft"
        >
          <IconButton code="icon-lishijilu" size={'md'} />
        </Dropdown>
        <IconButton code="icon-close" size={'md'} onClick={closeAIPlugin} />
      </Flex>
    );
  }, [chatList, currentChat, setCurrentChat, mainPageActiveTab]);

  return <AIChatHeader extraBtn={extraBtn} />;
};
