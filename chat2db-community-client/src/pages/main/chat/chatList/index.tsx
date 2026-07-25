import React, { memo, useEffect, useMemo, useState } from 'react';
import ListItem from './item';
import { useStyles } from './style';
import { useChatStore, shallow } from '@/store/chat';
import ChatBlankPage from '../chatBlankPage';
import Spin from '@/components/Spin';
import InfiniteScroll from 'react-infinite-scroll-component';
import { v4 as uuidv4 } from 'uuid';
import i18n from '@/i18n';
import ChatScroll from '@/components/ChatScroll';
import { useGlobalStore } from '@/store/global';

const ChatList = ({ trigger, forcibleTrigger }) => {
  const { styles } = useStyles();
  const { chatDetailsIds, nextChatList, chatDetails, currentChat, resetChatDetails } = useChatStore((state) => {
    return {
      chatDetailsIds: state.chatDetailsIds,
      chatDetails: state.chatDetails,
      nextChatList: state.nextChatList,
      currentChat: state.currentChat,
      resetChatDetails: state.resetChatDetails,
    };
  }, shallow);

  const chatScrollRef = React.useRef<any>(null);
  const [hasMore, setHasMore] = useState(true);

  const scrollId = useMemo(() => {
    return uuidv4();
  }, []);

  const renderEmpty = () => {
    return <ChatBlankPage />;
  };

  useEffect(() => {
    if (chatScrollRef.current) {
      setTimeout(() => {
        chatScrollRef.current.scrollToBottom({
          smooth: false,
          forcible: true,
        });
      }, 200);
    }
  }, [forcibleTrigger]);

  useEffect(() => {
    setHasMore(true);
  }, [currentChat.chat?.id, currentChat.workspace?.id, currentChat.dashboard?.id]);

  useEffect(() => {
    return () => {
      resetChatDetails(useGlobalStore.getState().mainPageActiveTab);
    };
  }, []);

  return (
    <div className={styles.container}>
      <ChatScroll className={styles.chatScroll} ref={chatScrollRef} id={scrollId} trigger={trigger}>
        <div style={{ flex: 1 }} />
        <Spin empty={renderEmpty()} isLoading={chatDetailsIds === null} isEmpty={chatDetailsIds?.length === 0}>
          <InfiniteScroll
            scrollableTarget={scrollId}
            dataLength={chatDetailsIds?.length || 0}
            hasMore={hasMore}
            scrollThreshold={50}
            next={() => {
              const lastId = chatDetails?.[chatDetailsIds![0]].question?.id;
              if (lastId && typeof lastId === 'number') {
                nextChatList(lastId).then((res) => {
                  setHasMore(res);
                });
              }
            }}
            inverse={true}
            style={{ overflow: 'visible', display: 'flex', flexDirection: 'column-reverse' }}
            loader={<div className={styles.centerDiv}>{i18n('common.text.loading')}</div>}
            endMessage={<div className={styles.centerDiv}>{i18n('common.text.noMore')}</div>}
          >
            {[...(chatDetailsIds || [])].reverse().map((item) => {
              return (
                <div key={item} className={styles.oneRoundChat}>
                  <ListItem id={item} />
                </div>
              );
            })}
          </InfiniteScroll>
        </Spin>
      </ChatScroll>
    </div>
  );
};

export default memo(ChatList);
