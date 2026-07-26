import { Button, Drawer, Flex, Popover } from 'antd';
import { useEffect, useRef, useState } from 'react';

import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import i18n from '@/i18n';
import notificationService from '@/service/notification';
import { ActionType, NotificationStatusType, NotificationType, NotificationVO } from '@/typings/notification';
import { openWebPage } from '@/utils/url';
import { Empty, EmptyImage, IconButton, IconfontSvg, Modal } from '@chat2db/ui';
import { RefreshCcw } from 'lucide-react';
import { useStyles } from './styles';

interface NotificationButtonProps {
  /** Drawer mode is controlled by the parent and does not render an icon button. */
  drawerMode?: boolean;
  open?: boolean;
  onClose?: () => void;
}

const NotificationButton = ({ drawerMode, open: externalOpen, onClose }: NotificationButtonProps = {}) => {
  const { styles, cx } = useStyles();
  const [list, setList] = useState<NotificationVO[]>([]);
  const [open, setOpen] = useState(false);
  const [hasUnread, setHasUnread] = useState(false);
  const [popSOpen, setSPopOpen] = useState(false);
  const [popSData, setSPopData] = useState<NotificationVO | null>(null);
  const [popNoSOpen, setNoSPopOpen] = useState(false);
  const [popNoSData, setNoSPopData] = useState<NotificationVO | null>(null);
  const [modalSizeWidth, setModalSizeWidth] = useState<number>();
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    queryNotificationList();
    queryUnreadCount();
    queryPopNotification();
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    const updateModalSize = () => {
      const modalWidth = window.innerWidth * 0.7; // Modal width is 70% of the viewport.
      const modalHeight = window.innerHeight * 0.7; // Modal height is 70% of the viewport.

      setModalSizeWidth(modalWidth < modalHeight ? modalWidth : modalHeight);
    };

    if (popSOpen) {
      updateModalSize();
      window.addEventListener('resize', updateModalSize);
    }

    return () => {
      window.removeEventListener('resize', updateModalSize);
    };
  }, [popSOpen]);

  const queryUnreadCount = () => {
    notificationService.queryUnreadCount().then((res) => {
      if (!mountedRef.current) return;
      setHasUnread(res > 0);
    });
  };

  const queryNotificationList = () => {
    notificationService.queryNotificationList({ pageNo: 1, pageSize: 50 }).then((res) => {
      if (!mountedRef.current) return;
      setList(res.data);
    });
  };

  const queryPopNotification = async () => {
    const res = await notificationService.queryPopNotification();
    if (!mountedRef.current) return;
    // Check localStorage for notifications already displayed, distinguished by ID.
    const popedNotificationId = localStorage.getItem(runtimeEditionConfig.notificationPopupStorageKey);
    // If the notification has not been shown, display it when valid.
    if (res && res.id && res.status === NotificationStatusType.Valid && res.id.toString() !== popedNotificationId) {
      localStorage.setItem(runtimeEditionConfig.notificationPopupStorageKey, res.id.toString());
      // S-level notification.
      if (res.type === NotificationType.S_CAMPAIGN) {
        setSPopOpen(true);
        setSPopData(res);
      }
      // Non-S-level notification.
      if (res.type === NotificationType.NOT_S_CAMPAIGN) {
        setNoSPopOpen(true);
        setNoSPopData(res);
      }
    }
  };

  const renderIcon = (type: NotificationType) => {
    switch (type) {
      case NotificationType.CAMPAIGN:
        return 'icon-colourful-active';
      case NotificationType.BLOG:
        return 'icon-colourful-update';
      case NotificationType.DOC:
        return 'icon-colourful-doc';
      default:
        return 'icon-colourful-doc';
    }
  };

  const contentRender = () => {
    // Non-S-level dialog.
    if (popNoSOpen && popNoSData) {
      return (
        <div className={styles.noSModal}>
          <img src={popNoSData?.content} />
          <div style={{ lineHeight: '20px' }}>{popNoSData.title}</div>
          <Button
            type="primary"
            onClick={() => {
              openWebPage(popNoSData.shareUrl);
              notificationService.takeNotificationAction({
                action: ActionType.READ,
                notificationId: popNoSData.id,
              });
              queryUnreadCount();
              setNoSPopOpen(false);
            }}
          >
            {i18n('notification.see')}
          </Button>
        </div>
      );
    }

    // Standard dialog.
    return (
      <div className={styles.wrapper}>
        <div className={styles.title}>
          <Flex align="center" gap={8}>
            <IconfontSvg code="icon-bell" />
            <span>{i18n('notification.title')}</span>
            <IconButton
              icon={RefreshCcw}
              size="sm"
              onClick={() => {
                queryUnreadCount();
                queryNotificationList();
              }}
            />
          </Flex>
          <IconButton code={'icon-close'} size="md" onClick={() => setOpen(false)} />
        </div>

        <div className={styles.listWrapper}>
          {list.map((item) => {
            return (
              <div
                key={item.id}
                className={cx(styles.item, item.status === NotificationStatusType.Invalid && styles.itemInvalid)}
                onClick={async () => {
                  openWebPage(item.shareUrl);
                  await notificationService.takeNotificationAction({
                    action: ActionType.READ,
                    notificationId: item.id,
                  });
                  queryUnreadCount();
                  queryNotificationList();
                }}
              >
                <div className={styles.itemIconWrapper}>
                  <IconfontSvg code={renderIcon(item.type)} className={styles.itemIcon} />
                  {item.unread && <div className={styles.itemIconUnread} />}
                </div>
                <Flex vertical gap={6}>
                  <div className={styles.itemTitle}>{item.title}</div>
                  <div className={styles.itemSubTitle}>{item.description}</div>
                </Flex>
              </div>
            );
          })}

          {list?.length === 0 && <Empty image={EmptyImage.Common} title={i18n('common.text.noData')} />}
        </div>
      </div>
    );
  };

  // Drawer mode embedded in the user menu.
  if (drawerMode) {
    return (
      <>
        <Drawer
          title={
            <Flex align="center" gap={8}>
              <IconfontSvg code="icon-bell" />
              <span>{i18n('notification.title')}</span>
              <IconButton
                icon={RefreshCcw}
                size="sm"
                onClick={() => {
                  queryUnreadCount();
                  queryNotificationList();
                }}
              />
            </Flex>
          }
          width={320}
          open={!!externalOpen}
          onClose={onClose}
          destroyOnClose
        >
          <div className={styles.listWrapper}>
            {list.map((item) => (
              <div
                key={item.id}
                className={cx(styles.item, item.status === NotificationStatusType.Invalid && styles.itemInvalid)}
                onClick={async () => {
                  openWebPage(item.shareUrl);
                  await notificationService.takeNotificationAction({
                    action: ActionType.READ,
                    notificationId: item.id,
                  });
                  queryUnreadCount();
                  queryNotificationList();
                }}
              >
                <div className={styles.itemIconWrapper}>
                  <IconfontSvg code={renderIcon(item.type)} className={styles.itemIcon} />
                  {item.unread && <div className={styles.itemIconUnread} />}
                </div>
                <Flex vertical gap={6}>
                  <div className={styles.itemTitle}>{item.title}</div>
                  <div className={styles.itemSubTitle}>{item.description}</div>
                </Flex>
              </div>
            ))}
            {list?.length === 0 && <Empty image={EmptyImage.Common} title={i18n('common.text.noData')} />}
          </div>
        </Drawer>

        {/* S-level dialog. */}
        {popSOpen && (
          <Modal
            className={styles.sModal}
            open={popSOpen}
            footer={null}
            maskClosable={false}
            mask={false}
            onCancel={() => setSPopOpen(false)}
            centered
            width={modalSizeWidth}
            maxHeight={'95vh'}
          >
            {popSData ? (
              <img
                src={popSData?.content}
                alt=""
                onClick={() => {
                  openWebPage(popSData?.shareUrl);
                  notificationService.takeNotificationAction({
                    action: ActionType.READ,
                    notificationId: popSData?.id,
                  });
                  queryUnreadCount();
                }}
              />
            ) : null}
          </Modal>
        )}
      </>
    );
  }

  // Icon mode: Popover and icon button by default.
  return (
    <>
      <Popover
        overlayClassName={styles.notification}
        trigger={'click'}
        placement="right"
        content={contentRender()}
        open={open || popNoSOpen}
        onOpenChange={(newOpen) => setOpen(newOpen)}
      >
        {/* <IconButton code={hasUnread ? 'icon-colourful-bell-red' : 'icon-bell'} /> */}
        <div className={styles.navIcon}>
          <IconButton code={'icon-bell'} />
          {hasUnread && <div className={styles.navUnreadIcon} />}
        </div>
      </Popover>

      {/* S-level dialog. */}
      {popSOpen && (
        <Modal
          className={styles.sModal}
          open={popSOpen}
          footer={null}
          maskClosable={false}
          mask={false}
          onCancel={() => setSPopOpen(false)}
          centered
          width={modalSizeWidth}
          maxHeight={'95vh'}
        >
          {popSData ? (
            <img
              src={popSData?.content}
              alt=""
              onClick={() => {
                openWebPage(popSData?.shareUrl);
                notificationService.takeNotificationAction({
                  action: ActionType.READ,
                  notificationId: popSData?.id,
                });
                queryUnreadCount();
              }}
            />
          ) : null}
        </Modal>
      )}
    </>
  );
};

export default NotificationButton;
