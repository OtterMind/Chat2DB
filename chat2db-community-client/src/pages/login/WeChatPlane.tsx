import { useEffect, useRef, useState, memo } from 'react';
import { useStyles } from './style';
import { history } from 'umi';
import { i18n, i18nElement } from '@/i18n';
import oauthServices from '@/service/enterprise/oauth';
import { getAllUrlParams } from '@/utils/url';

export interface Props {}

const WeChatPlane = memo<Props>(() => {
  const { styles } = useStyles();
  const [wechatQrCodeUrl, setWechatQrCodeUrl] = useState<string>('');
  const setIntervalRef = useRef<any>();
  const startTime = useRef<any>();
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    const generation = ++requestGenerationRef.current;
    getQRCode(generation);

    return () => {
      requestGenerationRef.current += 1;
      setIntervalRef.current && clearTimeout(setIntervalRef.current);
    };
  }, []);

  const getQRCode = (generation: number) => {
    oauthServices.getWechatQrCode().then((res) => {
      if (generation !== requestGenerationRef.current) {
        return;
      }
      startTime.current = new Date().getTime();
      setWechatQrCodeUrl(res.wechatQrCodeUrl);
      checkQRCodeStatus(res.token, generation);
    });
  };

  const checkQRCodeStatus = (token, generation: number) => {
    oauthServices
      .getWechatLoginStatus({
        token,
      })
      .then((res) => {
        if (generation !== requestGenerationRef.current) {
          return;
        }
        if (res) {
          const { redirect } = getAllUrlParams(window.location.href);
          if (redirect) {
            const path = decodeURIComponent(redirect);
            history.push(path);
          } else {
            history.push('/');
          }

          // history.push('/');
          return;
        }

        if (new Date().getTime() - startTime.current > 10 * 60 * 1000) {
          clearTimeout(setIntervalRef.current);
          getQRCode(generation);
          return;
        }

        setIntervalRef.current = setTimeout(() => {
          checkQRCodeStatus(token, generation);
        }, 2000);
      });
  };

  return (
    <div className={styles.wechatBox}>
      <div className={styles.loginTip}>
        {i18nElement(
          'login.text.wechatLoginTips',
          <span className={styles.wechatSpan}>{i18n('login.text.wechatScan')}</span>,
        )}
      </div>
      <div className={styles.qrcodeWrapper}>
        {wechatQrCodeUrl && <img className={styles.loginQRCode} src={wechatQrCodeUrl} />}
        {/* {qrCodeStatus !== 'loop' && (
        )} */}
        {/* <div className={styles.loginQRCodeMask} onClick={handleGetQRCodeAgain}>
          {qrCodeStatus === 'overdue' && (
            <>
              <Iconfont code="&#xec08;" size={32} />
              <div>Refresh QR code</div>
            </>
          )}
        </div> */}
      </div>
    </div>
  );
});

export default WeChatPlane;
