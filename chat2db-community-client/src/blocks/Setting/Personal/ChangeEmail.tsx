import { memo, useState, useRef, useEffect } from 'react';
import { Input, Button, Form } from 'antd';
import userServices from '@/service/enterprise/user';
import { createStyles } from 'antd-style';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';

export const useStyles = createStyles(({ css, token }) => {
  return {
    sendCode: css`
      margin-bottom: 16px;
      button:hover {
        background-color: transparent;
        color: ${token.colorPrimary};
      }
    `,
    changeEmailBox: css`
      width: 500px;
      max-width: 70%;
    `,
  };
});

interface IProps {
  updateUser: any;
  curUser: any;
}

export default memo<IProps>((props) => {
  const { curUser, updateUser } = props;
  const { styles } = useStyles();
  const [codeCountDown, setCodeCountDown] = useState<number | null>(null);
  const [sendCodeLoading, setSendCodeLoading] = useState<boolean>(false); // Verification-code loading state.
  const [sendCodeCount, setSendCodeCount] = useState<number>(0); // Number of verification-code sends.
  const [submitLoading, setSubmitLoading] = useState<boolean>(false);
  const [form] = Form.useForm();
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  const onFinish = (formValue: any) => {
    setSubmitLoading(true);
    updateUser({ email: formValue.email, passcode: formValue.passcode })
      .then(() => {
        staticMessage.success(i18n('common.message.modifySuccessfully'));
        form.resetFields();
      })
      .finally(() => {
        setSubmitLoading(false);
      });
  };

  const onFinishFailed = (errorInfo: any) => {
    console.log('Failed:', errorInfo);
  };

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

  return (
    <div className={styles.changeEmailBox}>
      <Form form={form} layout="vertical" name="login" onFinish={onFinish} onFinishFailed={onFinishFailed}>
        <Form.Item name="email" label={i18n('common.label.email')}>
          <div>{curUser.email || i18n('setting.text.notEmail')}</div>
        </Form.Item>
        <Form.Item
          name="email"
          label={i18n('login.label.newEmail')}
          rules={[
            { required: true, message: i18n('setting.tips.requiredEmail') },
            { type: 'email', message: i18n('setting.tips.errorEmail') },
          ]}
        >
          <Input type="email" placeholder={i18n('common.label.email')} />
        </Form.Item>
        <Form.Item
          name="passcode"
          label={i18n('login.label.verificationCode')}
          className={styles.sendCode}
          rules={[
            { required: true, message: i18n('login.tips.requiredVerificationCode') },
            {
              pattern: /^\d{6}$/,
              message: i18n('login.tips.errorEmailCode'),
            },
          ]}
        >
          <Input
            placeholder={i18n('login.placeholder.enterVerificationCode')}
            addonAfter={
              <Button
                size="small"
                loading={sendCodeLoading}
                type="text"
                disabled={codeCountDown !== null}
                onClick={handleSendSMSAndStartTiming}
              >
                {codeCountDown === null
                  ? sendCodeCount > 0
                    ? i18n('login.button.resend')
                    : i18n('login.button.sendCode')
                  : `${codeCountDown}s`}
              </Button>
            }
          />
        </Form.Item>
        <Form.Item>
          <Button htmlType="submit" type="primary" loading={submitLoading}>
            {i18n('setting.button.changeEmail')}
          </Button>
        </Form.Item>
      </Form>
    </div>
  );
});
