import React, { FC } from 'react';
import { Flex } from 'antd';
import { EditText } from '@chat2db/ui';
import { useChatStore } from '@/store/chat';
import { useStyles } from './style';
import { AIChatHeaderInPanel } from './HeaderInPanel';
import { useGlobalStore } from '@/store/global';
export interface AIChatHeaderProps {
  extraBtn?: React.ReactNode;
}

const AIChatHeader: FC<AIChatHeaderProps> = ({ extraBtn }) => {
  const { styles } = useStyles();

  const { currentChat, updateChatInfo } = useChatStore((state) => ({
    currentChat: state.currentChat,
    updateChatInfo: state.updateChatInfo,
  }));
  const { mainPageActiveTab } = useGlobalStore((state) => ({
    mainPageActiveTab: state.mainPageActiveTab,
  }));

  const { title } = currentChat?.[mainPageActiveTab] || {};

  const handleUpdateTitle = (value: string) => {
    updateChatInfo({ id: currentChat?.[mainPageActiveTab]?.id, title: value });
  };

  return (
    <div className={styles.container}>
      <Flex className={styles.titleContainer} align="center" gap={6}>
        {/* <div className={styles.avatar}>💬</div> */}
        <EditText className={styles.title} onBlur={handleUpdateTitle}>
          {title || ''}
        </EditText>
      </Flex>
      <Flex className={styles.extraBtnContainer} gap={8} align="center" justify="flex-end">
        {extraBtn}
      </Flex>
    </div>
  );
};

export default AIChatHeader;
export { AIChatHeaderInPanel };
