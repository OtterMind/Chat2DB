import { useEffect } from 'react';
import PageTitle from '@/components/PageTitle';
import { useStyles } from './style';
import { Empty, EmptyImage, Icon, IconButton, ListItem, MenuLabel } from '@chat2db/ui';
import { MoreVertical, Trash2 } from 'lucide-react';
import InfiniteScroll from 'react-infinite-scroll-component';
import { Skeleton } from 'antd';
import { useChatStore } from '@/store/chat';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';

const scrollId = 'chatConversationListDiv';

const chatMenuList = () => {
  const { styles } = useStyles();
  const { chatList, currentChat, chatListParams, setCurrentChat, queryChatList, deleteChat } = useChatStore(
    (state) => {
      return {
        chatList: state.chatList,
        currentChat: state.currentChat,
        chatListParams: state.chatListParams,
        queryChatList: state.queryChatList,
        setCurrentChat: state.setCurrentChat,
        deleteChat: state.deleteChat,
      };
    },
  );

  const { openUnifiedConfirmationModal } = useGlobalStore((state) => {
    return {
      openUnifiedConfirmationModal: state.openUnifiedConfirmationModal,
    };
  });

  useEffect(() => {
    if (!chatList.length) {
      queryChatList();
      return;
    }

    if (currentChat?.['chat']?.id) {
      setCurrentChat({
        ...currentChat,
        ['chat']: chatList[0],
      });
    }
  }, []);

  return (
    <div className={styles.container}>
      <PageTitle title={i18n('chat.page.title')} style={{ padding: '16px' }} />
      {/* <SearchBar
        shortKey="K"
        enableShortKey
        className={styles.search}
        placeholder="Search"
        onPressEnter={() => {
          console.log('onPressEnter');
        }}
      /> */}
      {chatList.length ? (
        <div className={styles.flowWrapper} id={scrollId}>
          <InfiniteScroll
            scrollableTarget={scrollId}
            dataLength={chatList.length}
            next={queryChatList}
            hasMore={!!chatListParams.hasNextPage}
            scrollThreshold={'50px'}
            loader={
              <Skeleton
                active
                paragraph={{ width: '80px', rows: 5 }}
                style={{ marginBottom: '32px', paddingLeft: '8px', paddingTop: '8px' }}
              />
            }
            // endMessage={
            //   <Divider plain style={{ margin: '12px 0' }}>
            //     {i18n('chat.menu.noMore')}
            //   </Divider>
            // }
          >
            {(chatList || []).map((item) => (
              <ListItem
                className={styles.listItem}
                key={item.id}
                // code={renderListItemCode(item.source!)}
                title={item.title ?? i18n('chat.page.empty.title')}
                isActive={currentChat?.['chat']?.id === item.id}
                onClick={() => {
                  if (item.id !== currentChat?.['chat']?.id) {
                    setCurrentChat({
                      ...currentChat,
                      ['chat']: item,
                    });
                  }
                }}
                addonAfter={<IconButton size="md" icon={MoreVertical} />}
                dropdownProps={{
                  trigger: ['contextMenu'],
                  menu: {
                    items: [
                      {
                        key: '3',
                        label: <MenuLabel label={i18n('chat.menu.delete')} />,
                        danger: true,
                        icon: <Icon icon={Trash2} size="md" />,
                        onClick: ({ domEvent }) => {
                          domEvent.stopPropagation();
                          openUnifiedConfirmationModal({
                            title: i18n('common.text.deleteConfirmTitle'),
                            content: i18n('chat.menu.delete.secondConfirm'),
                            onOk: () => {
                              if (item.id) {
                                return deleteChat(item.id);
                              }
                              return Promise.resolve();
                            },
                          });
                        },
                      },
                    ],
                  },
                }}
              />
            ))}
          </InfiniteScroll>
        </div>
      ) : (
        <Empty className={styles.empty} image={EmptyImage.ChatList} title={i18n('chat.menu.empty.text')} />
      )}
      {/* <UserStatus /> */}
    </div>
  );
};

export default chatMenuList;
