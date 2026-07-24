import watchaLogo from '@/assets/img/watcha.png';
import { SvgIcons } from '@/components/SvgIcons';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import i18n from '@/i18n';
import { ICommandLineRequest } from '@/service/commandLine/commandLine';
import oauthServices from '@/service/enterprise/oauth';
import userServices from '@/service/enterprise/user';
import { clearStore } from '@/store';
import { useGlobalStore } from '@/store/global';
import { LoginDetailType } from '@/typings/enterprise/oauth';
import { removeOpenScreenAnimation } from '@/utils/dom';
import { isDesktop } from '@/utils/env';
import { getAllUrlParams, getUrlParam, openWebPage } from '@/utils/url';
import { Icon } from '@chat2db/ui';
import { Button, Divider, Form, Input } from 'antd';
import { memo, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { history } from 'umi';
import { v4 as uuidv4 } from 'uuid';
import { useStyles } from './style';
import WeChatPlane from './WeChatPlane';

interface IProps {
  className?: string;
}
export default memo<IProps>(() => {
  const { appUrlConfig, loginType, setLoginType, appConfig } = useGlobalStore((s) => ({
    loginType: s.loginType,
    setLoginType: s.setLoginType,
    appConfig: s.appConfig,
    appUrlConfig: s.appUrlConfig,
  }));
  const { isCN } = appConfig;
  const [codeCountDown, setCodeCountDown] = useState<number | null>(null);
  const [sendCodeLoading, setSendCodeLoading] = useState<boolean>(false); // Verification-code request state.
  const [sendCodeCount, setSendCodeCount] = useState<number>(0); // Number of verification-code requests.
  const [showForgetPassword, setShowForgetPassword] = useState<boolean>(false); // Whether to reset the password.
  const [submitLoading, setSubmitLoading] = useState<boolean>(false);
  const [isWechatLogin, setIsWechatLogin] = useState<boolean>(isCN);
  const [form] = Form.useForm();
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [loginUrls, setLoginUrls] = useState<{
    githubLoginUrl: string;
    googleLoginUrl: string;
    watchaLoginUrl?: string;
  } | null>(null);
  const { styles, cx } = useStyles();

  const markGoogleAdsSignUpPending = () => {
    if (!runtimeEditionConfig.googleAds) {
      return;
    }
    try {
      sessionStorage.setItem(runtimeEditionConfig.googleAdsSignupPendingStorageKey, '1');
    } catch {
      // ignore
    }
  };

  useEffect(() => {
    if (!runtimeEditionConfig.commercialAccount) {
      history.replace('/');
      return;
    }

    if (isDesktop) {
      return;
    }

    const redirectUrl = getUrlParam('redirect');
    if (redirectUrl) {
      const currentTime = new Date().getTime();
      localStorage.setItem(
        runtimeEditionConfig.loginRedirectStorageKey,
        JSON.stringify({
          url: redirectUrl,
          timestamp: currentTime,
        }),
      );

      // Set a timeout to remove the item after 30 seconds
      setTimeout(() => {
        localStorage.removeItem(runtimeEditionConfig.loginRedirectStorageKey);
      }, 30000);
    }
  }, []);

  useLayoutEffect(() => {
    if (runtimeEditionConfig.commercialAccount) {
      clearStore();
    }
    removeOpenScreenAnimation();
  }, []);

  useEffect(() => {
    setIsWechatLogin(isCN);
  }, [appConfig.curCountry]);

  useEffect(() => {
    if (!runtimeEditionConfig.commercialAccount) {
      setLoginUrls(null);
      return;
    }
    oauthServices
      .getLoginUrl()
      .then((res) => {
        setLoginUrls(res);
      })
      .catch((err: any) => {
        console.log(err);
      });
  }, [appConfig.curCountry]);

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  if (!runtimeEditionConfig.commercialAccount) {
    return null;
  }

  const handleSendSMSAndStartTiming = () => {
    // Validate the email address.
    form.validateFields(['email']).then(() => {
      const email = form.getFieldValue('email');
      setSendCodeLoading(true);
      userServices
        .sendEmailSMS({ email })
        .then(() => {
          let countdown = 60;
          setCodeCountDown(countdown);
          if (timerRef.current) {
            clearInterval(timerRef.current);
          }
          timerRef.current = setInterval(() => {
            countdown -= 1;
            setCodeCountDown(countdown);
            if (countdown === 0) {
              clearInterval(timerRef.current!);
              timerRef.current = null;
              setCodeCountDown(null);
            }
          }, 1000);
        })
        .finally(() => {
          setSendCodeCount(sendCodeCount + 1);
          setSendCodeLoading(false);
        });
    });
  };

  const handleLogin = (formValue: any) => {
    const p = {
      loginType: loginType,
      ...formValue,
    };
    setSubmitLoading(true);
    oauthServices
      .userLogin(p)
      .then(() => {
        // Mark authentication through the login page so the app can report
        // Google Ads registration without relying on timezone-sensitive createTime.
        markGoogleAdsSignUpPending();
        const { redirect } = getAllUrlParams(window.location.href);
        if (redirect) {
          const path = decodeURIComponent(redirect);
          if (path.includes('/login')) {
            history.push('/');
          } else {
            history.push(path);
          }
        } else {
          history.push('/');
        }
      })
      .catch((error) => {
        form.setFields([
          {
            name: 'password',
            errors: [error.errorMessage],
          },
          {
            name: 'passcode',
            errors: [error.errorMessage],
          },
        ]);
      })
      .finally(() => {
        setTimeout(() => {
          setSubmitLoading(false);
        }, 500);
      });
  };

  const onFinish = (formValue: any) => {
    handleLogin(formValue);
  };

  const onFinishFailed = (errorInfo: any) => {
    console.log('Failed:', errorInfo);
  };

  const handleChangeLoginType = (type: LoginDetailType) => {
    setLoginType(type);
  };

  const handleThirdPartyLogin = (key: string) => {
    if (loginUrls?.[key]) {
      // Third-party login redirects away and back; sessionStorage survives navigation within the same tab.
      // Set the marker first so the app can report Google Ads registration for OAuth sign-ups after returning.
      markGoogleAdsSignUpPending();
      const urlToOpen = loginUrls[key];
      if (typeof window.javaQuery === 'function') {
        const language = useGlobalStore.getState().baseSetting.language;
        const id = uuidv4();
        // In JCEF, ask Java to open the external browser.
        console.log(`JCEF: Requesting Java to open external URL: ${urlToOpen}`);
        const data: ICommandLineRequest = {
          requestUrl: 'api/user/authenticate',
          method: 'POST',
          message: {
            urlToOpen: urlToOpen,
          },
        };
        const requestPayload = {
          actionType: 'login',
          headers: {
            'Accept-Language': language,
            'Time-Zone': new Intl.DateTimeFormat().resolvedOptions().timeZone,
          },
          uuid: id,
          ...data,
        };

        if (typeof window.javaQuery === 'function') {
          window.javaQuery({
            request: JSON.stringify(requestPayload),
            onSuccess: () => {
              console.log(`JCEF: Java successfully handled openExternalUrl for ${urlToOpen}`);
            },
            onFailure: (errorCode: number, errorMessage: string) => {
              console.error(`JCEF bridge error for system/openExternalUrl: ${errorCode} - ${errorMessage}`);
            },
          });
        }
        return;
      } else if (isDesktop) {
        console.log(`Desktop (non-JCEF): Opening URL in new window/tab: ${urlToOpen}`);
        openWebPage(urlToOpen, '_blank');
        return;
      }
      // Standard web browser environment.
      console.log(`Web Browser: Opening URL in self: ${urlToOpen}`);
      openWebPage(urlToOpen, '_self'); // Use '_blank' instead to open a new tab.
    } else {
      console.info(`No login URL found for key: ${key}`);
      alert(`未找到 '${key}' 对应的登录链接。`);
    }
  };

  // Show watcha.cn passwordless login only when the domestic edition provides watchaLoginUrl.
  const renderWatchaLoginButton = () => {
    if (!loginUrls?.watchaLoginUrl) {
      return null;
    }
    return (
      <Button
        type="default"
        size="large"
        className={styles.thirdPartyLoginButton}
        onClick={() => {
          handleThirdPartyLogin('watchaLoginUrl');
        }}
      >
        <img src={watchaLogo} className={styles.icon} alt="" />
        {i18n('login.text.signInWithWatcha')}
      </Button>
    );
  };

  const renderOverseaLogin = () => {
    return (
      <>
        {isCN && (
          <>
            <Button
              type="default"
              size="large"
              className={cx(styles.thirdPartyLoginButton, styles.wechatLoginButton)}
              onClick={() => {
                setIsWechatLogin(true);
              }}
            >
              <Icon icon="&#xe69b;" />
              {i18n('login.text.signInWithWeChat')}
            </Button>
            {renderWatchaLoginButton()}
          </>
        )}

        <Button
          type="default"
          size="large"
          className={styles.thirdPartyLoginButton}
          onClick={() => {
            handleThirdPartyLogin('googleLoginUrl');
          }}
        >
          <SvgIcons.google className={styles.icon} />
          {i18n('login.text.continueWithGoogle')}
        </Button>

        <Button
          type="primary"
          size="large"
          className={styles.thirdPartyLoginButton}
          onClick={() => {
            handleThirdPartyLogin('githubLoginUrl');
          }}
        >
          <SvgIcons.github className={styles.icon} />
          {i18n('login.text.continueWithGitHub')}
        </Button>
        <Divider plain>{i18n('login.text.or')}</Divider>

        <Form form={form} name="login" onFinish={onFinish} onFinishFailed={onFinishFailed}>
          <Form.Item
            name="email"
            rules={[
              { required: true, message: i18n('setting.tips.requiredEmail') },
              { type: 'email', message: i18n('setting.tips.errorEmail') },
            ]}
          >
            <Input type="email" size="large" placeholder={i18n('common.label.email')} />
          </Form.Item>
          {loginType !== LoginDetailType.EMAIL_PASSWORD && (
            <Form.Item name="passcode" className={styles.sendCode}>
              <Input
                size="large"
                placeholder={i18n('login.tips.requiredVerificationCode')}
                addonAfter={
                  <Button
                    loading={sendCodeLoading}
                    type="text"
                    disabled={codeCountDown !== null}
                    onClick={handleSendSMSAndStartTiming}
                  >
                    {codeCountDown === null ? (sendCodeCount > 0 ? 'Resend' : 'Send Code') : `${codeCountDown}s`}
                  </Button>
                }
              />
            </Form.Item>
          )}
          {sendCodeCount > 0 && loginType === LoginDetailType.EMAIL_PASSCODE && (
            <div className={styles.codeCountDownTips}>{i18n('login.tips.sendVerificationCode')}</div>
          )}
          {loginType === LoginDetailType.EMAIL_PASSWORD && (
            <Form.Item
              name="password"
              rules={[
                { required: true, message: i18n('login.tips.passwordsMustMatch') },
                {
                  pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/,
                  message: i18n('login.tips.checkoutPassword'),
                },
              ]}
            >
              <Input.Password size="large" placeholder={i18n('login.text.password')} />
            </Form.Item>
          )}
          <div
            className={cx(styles.forgetPasswordTips, {
              [styles.activeForgetPasswordTips]: showForgetPassword,
            })}
          >
            {i18n('login.tips.resetPasswords')}
          </div>
          <div className={styles.emailLoginFooter}>
            <div
              className={styles.tipsByLink}
              onClick={() => {
                handleChangeLoginType(
                  loginType === LoginDetailType.EMAIL_PASSCODE
                    ? LoginDetailType.EMAIL_PASSWORD
                    : LoginDetailType.EMAIL_PASSCODE,
                );
              }}
            >
              {loginType === LoginDetailType.EMAIL_PASSCODE
                ? i18n('login.text.usePassword')
                : i18n('login.text.useVerificationCode')}
            </div>
            <div
              className={styles.tipsByLink}
              onClick={() => {
                setShowForgetPassword(!showForgetPassword);
              }}
            >
              {i18n('login.button.forgetPassword')}
            </div>
          </div>
          <Form.Item>
            <Button
              htmlType="submit"
              type="primary"
              size="large"
              loading={submitLoading}
              className={styles.loginButton}
            >
              {i18n('login.text.signIn')}
            </Button>
          </Form.Item>
        </Form>
      </>
    );
  };

  return (
    <div className={styles.loginBox}>
      <div className={styles.welcomeBack}>{i18n('login.text.welcomeBack')}!</div>
      <div className={styles.subheading}>{i18n('login.text.signInToChat2DB')}</div>
      {isWechatLogin ? (
        <>
          <WeChatPlane />
          {renderWatchaLoginButton()}
          <Button
            type="default"
            size="large"
            className={styles.thirdPartyLoginButton}
            onClick={() => {
              setIsWechatLogin(false);
            }}
          >
            {i18n('login.text.signInWithOther')}
          </Button>
        </>
      ) : (
        renderOverseaLogin()
      )}
      <div className={styles.agreement}>
        {i18n('login.text.agreement')}
        <a href={appUrlConfig.SERVICE_AGREEMENT} target="_blank" rel="noopener noreferrer">
          {i18n('login.text.termsOfService')}
        </a>
        {i18n('common.text.and')}
        <a href={appUrlConfig.PRIVACY_POLICY} target="_blank" rel="noopener noreferrer">
          {i18n('login.text.privacyPolicy')}
        </a>
        .
      </div>
    </div>
  );
});
