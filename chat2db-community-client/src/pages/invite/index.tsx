import { useEffect, useRef, useState } from 'react';
import { useStyles } from './style';
import { Input, TextArea } from '@chat2db/ui';
import Logo from '@/components/Logo';
import { useGlobalStore } from '@/store/global';
import { Button, Form } from 'antd';
import organizationService from '@/service/enterprise/organization';
import { history } from 'umi';
import { getAllUrlParams } from '@/utils/url';
import { IOrganizationVO } from '@/typings/enterprise/organization';
import i18n, { i18nElement } from '@/i18n';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
enum PageStep {
  None,
  Init,
  Confirm,
  Success,
  Empty,
}
const formItemLayout = {
  labelCol: {
    span: 24, // Number of columns occupied by the label.
  },
  wrapperCol: {
    span: 24, // Number of columns occupied by the input.
  },
};
const Invite = () => {
  const [pageStep, setPageStep] = useState<PageStep>(PageStep.None);
  const [inviteOrg, setInviteOrg] = useState<IOrganizationVO | null>(null);
  const { styles } = useStyles();
  const { appUrlConfig } = useGlobalStore((state) => ({
    appUrlConfig: state.appUrlConfig,
  }));
  const [form] = Form.useForm();
  const requestGenerationRef = useRef(0);

  useEffect(() => {
    queryTeamInfo();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const queryTeamInfo = async () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    const { organizationCode, inviterId } = getAllUrlParams();
    const res = await organizationService.queryOrgByTeamCode({ teamCode: organizationCode, inviterId });
    if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
    setInviteOrg(res);
    setPageStep(res ? PageStep.Init : PageStep.Empty);
  };

  const renderInit = () => {
    return (
      <div className={styles.content}>
        <div className={styles.contentText}>
          <div>
            {i18nElement(
              'team.invite.init.content1',
              inviteOrg?.inviterName,
              <span className={styles.highlight}>Chat2DB</span>,
            )}
          </div>
          <div>{i18n('team.invite.init.content2', inviteOrg?.name)}</div>
        </div>
        <Button
          onClick={() => {
            setPageStep(PageStep.Confirm);
          }}
          size="large"
          type="primary"
        >
          {i18n('team.invite.init.next')}
        </Button>
      </div>
    );
  };
  const renderConfirm = () => {
    return (
      <div className={styles.content}>
        <div className={styles.contentText}>
          <div>{i18n('team.invite.confirm.content1')}</div>
          <div>{i18n('team.invite.confirm.content2')}</div>
        </div>
        <Form {...formItemLayout} form={form} autoComplete="off">
          <Form.Item
            name={'name'}
            label={i18n('team.invite.confirm.name')}
            rules={[{ required: true, message: i18n('team.invite.confirm.name.placeholder') }]}
          >
            <Input placeholder={i18n('team.invite.confirm.name.placeholder')} maxLength={50} />
          </Form.Item>
          <Form.Item name={'reason'} label={i18n('team.invite.confirm.reason')}>
            <TextArea
              placeholder={i18n('team.invite.confirm.reason.placeholder')}
              autoSize={{ minRows: 1, maxRows: 2 }}
              maxLength={200}
            />
          </Form.Item>
        </Form>
        <Button
          onClick={async () => {
            await form.validateFields();

            const formValue = form.getFieldsValue();
            const { organizationCode } = getAllUrlParams();
            await organizationService.joinOrganization({
              ...formValue,
              organizationCode,
            });

            setPageStep(PageStep.Success);
          }}
          size="large"
          type="primary"
          style={{ margin: '20px 0' }}
        >
          {i18n('team.invite.confirm.submit')}
        </Button>
      </div>
    );
  };

  const renderSuccess = () => {
    return (
      <div className={styles.content}>
        <div className={styles.contentText}>
          <div>{i18n('team.invite.success.content1')}</div>
          <div>{i18n('team.invite.success.content2')}</div>
        </div>
        <Button
          onClick={() => {
            // Navigate to the workspace page.
            history.push('/');
          }}
          size="large"
          type="primary"
        >
          {i18n('team.invite.success.finish')}
        </Button>
      </div>
    );
  };
  const renderEmpty = () => {
    return <div>Empty</div>;
  };
  const renderContent = () => {
    switch (pageStep) {
      case PageStep.Init:
        return renderInit();
      case PageStep.Confirm:
        return renderConfirm();
      case PageStep.Success:
        return renderSuccess();
      case PageStep.Empty:
        return renderEmpty();
      default:
        return null;
    }
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.cardWrapper}>
        <div className={styles.headerWrapper}>
          <Logo type="imageWithText" size={50} style={{ marginBottom: '20px' }} />
          <span>{i18n('team.invite.title.subtitle1')} </span>
          <span>{i18n('team.invite.title.subtitle2')}</span>

          {/* <div className={styles.leftBall} />
          <div className={styles.rightBall} /> */}
        </div>

        <div className={styles.contentWrapper}>
          {renderContent()}

          <a href={appUrlConfig.DOCS_URL} target="_blank" rel="noreferrer">
            {i18n('team.invite.question')}
          </a>
        </div>
      </div>
    </div>
  );
};

export default Invite;
