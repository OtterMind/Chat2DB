import React, { memo, useEffect, forwardRef, ForwardedRef, useImperativeHandle } from 'react';
import configService from '@/service/config';
import styles from './index.less';
import { notification, Button, Space } from 'antd';
import { compareVersion } from '@/utils';
import { IUpdateDetectionData } from '../index';
import i18n, { i18nElement } from '@/i18n';
import Iconfont from '@/components/Iconfont';

export enum UpdatedStatusEnum {
  // 未更新
  NOT_UPDATED = 'NOT_UPDATED',
  // 更新中
  UPDATING = 'UPDATING',
  // 更新完成
  UPDATED = 'UPDATED',
  // 更新失败
  UPDATE_FAILED = 'UPDATE_FAILED',
  // 超时
  TIMEOUT = 'TIMEOUT',
}

interface IProps {
  openSettingModal: (number) => void;
  updateDetectionData: IUpdateDetectionData | null;
  setUpdateDetectionData: (data: IUpdateDetectionData) => void;
}

export interface IUpdateDetectionRef {
  openDownload: (data: IUpdateDetectionData) => void;
}

// 轮训间隔时间
const INTERVAL_TIME = 5000;
// 最大轮训次数
const MAX_TIMES = 200;

const UpdateDetection = memo(
  forwardRef((props: IProps, ref: ForwardedRef<IUpdateDetectionRef>) => {
    const { openSettingModal, setUpdateDetectionData } = props;
    const [notificationApi, notificationDom] = notification.useNotification();
    const timesRef = React.useRef(0);

    // useEffect(() => {
    //   checkUpdate();
    // }, []);

    const close = () => {};

    // 检测是否有可更新的版本
    function checkUpdate() {
      configService
        .getLatestVersion({
          currentVersion: __APP_VERSION__,
        })
        .then((res) => {
          // 如果是服务端，那么就不用更新
          if(!res) {
            return
          }
          if (res.desktop === false) {
            return;
          }

          const _updateDetectionData = {
            ...res,
            needUpdate: compareVersion(res.version, __APP_VERSION__) === 1,
            updatedStatusEnum: UpdatedStatusEnum.NOT_UPDATED,
          };

          setUpdateDetectionData(_updateDetectionData);

          // 如果监测到localStorage里面有存的老版本，那么就提示首次更新
          const lastLatestVersion = localStorage.getItem('last-latest-version');
          if (lastLatestVersion && compareVersion(lastLatestVersion, __APP_VERSION__) === -1) {
            openNotificationUpdated();
          }

          // 最新版本存入localStorage
          localStorage.setItem('last-latest-version', __APP_VERSION__);

          // 如果是最新版本，那么就不用更新
          if (compareVersion(res.version, __APP_VERSION__) !== 1) {
            return;
          }

          // 如果用户点过知道那么，就不用提示更新
          if (localStorage.getItem('i-see-latest-version') === res.version && res.type === 'manual') {
            return;
          }

          // 如果是自动更新那么就轮询调后端接口，判断是否更新完成
          if (res.type === 'auto') {
            timesRef.current = 0;
            openDownload(_updateDetectionData);
          } else {
            // 如果是手动更新，那么就提示下载
            if (res.version) {
              openNotificationManual(res);
            }
          }
        });
    }

    function isUpdateSuccess(_updateDetectionData) {
      if (timesRef.current > MAX_TIMES) {
        setUpdateDetectionData({
          ..._updateDetectionData!,
          updatedStatusEnum: UpdatedStatusEnum.TIMEOUT,
        });
        return;
      }
      timesRef.current = timesRef.current + 1;
      if (!_updateDetectionData?.version) {
        return;
      }
      configService
        .isUpdateSuccess({
          version: _updateDetectionData.version,
        })
        .then((res) => {
          if (res) {
            setUpdateDetectionData({
              ..._updateDetectionData!,
              updatedStatusEnum: UpdatedStatusEnum.UPDATED,
            });
            openNotificationAuto(_updateDetectionData);
          } else {
            setTimeout(() => {
              isUpdateSuccess(_updateDetectionData);
            }, INTERVAL_TIME);
          }
        });
    }

    // function go() {
    //   // window.open(responseText.downloadLink);
    //   notificationApi.destroy();
    // }

    const handleISee = (_updateDetectionData) => {
      // 存入localStorage
      localStorage.setItem('i-see-latest-version', _updateDetectionData?.version || '');
      notificationApi.destroy();
    };

    const openNotificationAuto = (_updateDetectionData) => {
      const key = `open${Date.now()}`;
      const btn = (
        <Space>
          <Button
            type="link"
            size="small"
            onClick={() => {
              handleISee(_updateDetectionData);
            }}
          >
            {i18n('setting.button.iSee')}
          </Button>
          {/* <Button
            type="primary"
            size="small"
            onClick={() => {
              go();
            }}
          >
            {i18n('setting.button.restart')}
          </Button> */}
        </Space>
      );
      notificationApi.open({
        className: styles.notification,
        duration: null,
        message: (
          <div className={styles.updateReminder}>
            <div className={styles.bell}>
              <Iconfont code="&#xe661;" />
            </div>
            {i18n('common.text.updateReminder')}
          </div>
        ),
        description: i18n('setting.text.newEditionIsReady'),
        style: {
          width: 260,
          backgroundColor: 'var(--color-bg-subtle)',
        },
        btn,
        key,
        onClose: close,
      });
    };

    const openNotificationManual = (_updateDetectionData) => {
      const key = `open${Date.now()}`;
      const btn = (
        <div className={styles.notificationBtnBox}>
          <Button
            type="link"
            size="small"
            onClick={() => {
              handleISee(_updateDetectionData);
            }}
          >
            {i18n('setting.button.iSee')}
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => {
              openSettingModal(3);
              notificationApi.destroy();
            }}
          >
            {i18n('setting.button.goToUpdate')}
          </Button>
        </div>
      );
      notificationApi.open({
        duration: null,
        className: styles.notification,
        message: (
          <div className={styles.updateReminder}>
            <div className={styles.bell}>
              <Iconfont code="&#xe661;" />
            </div>
            {i18n('common.text.updateReminder')}
          </div>
        ),
        description: i18nElement(
          'setting.text.discoverNewVersion',
          <span className={styles.versionText}>v{_updateDetectionData.version}</span>,
        ),
        style: {
          width: 260,
          backgroundColor: 'var(--color-bg-subtle)',
        },
        btn,
        key,
        onClose: close,
      });
    };

    // 首次更新完成提示
    const openNotificationUpdated = () => {
      const key = `open${Date.now()}`;
      notificationApi.open({
        className: styles.notification,
        duration: 6,
        message: (
          <div className={styles.updateReminder}>
            <div className={styles.bell}>
              <Iconfont code="&#xe661;" />
            </div>
            {i18n('common.text.updateReminder')}
          </div>
        ),
        description: i18n('setting.text.UpdatedLatestVersion', `v${__APP_VERSION__}`),
        style: {
          width: 310,
          backgroundColor: 'var(--color-bg-subtle)',
        },
        btn: null,
        key,
        onClose: close,
      });
    };

    const openDownload = (_updateDetectionData: IUpdateDetectionData) => {
      if (!_updateDetectionData) {
        return;
      }
      configService.updateDesktopVersion(_updateDetectionData).then(() => {
        timesRef.current = 0;
        isUpdateSuccess(_updateDetectionData);
        setUpdateDetectionData({
          ..._updateDetectionData,
          updatedStatusEnum: UpdatedStatusEnum.UPDATING,
        });
      });
    };

    useImperativeHandle(ref, () => ({
      openDownload,
    }));

    return <>{notificationDom}</>;
  }),
);

export default UpdateDetection;
