import { Modal, Input, TextArea, ModalProps, IconfontSvg, Empty, EmptyImage } from '@chat2db/ui';
import { Button, Flex, Form, Select } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { useStyles } from './style';
import domesticCity from '@/data/domestic_city.json';
import abroadCity from '@/data/abroad_city.json';

import domesticIndustries from '@/data/domestic_industry.json';
import abroadIndustries from '@/data/abroad_industry.json';
import domesticRoleCode from '@/data/domestic_org_role.json';
import abroadRoleCode from '@/data/abroad_org_role.json';

import { useOrgStore } from '@/store/organization';
import { IOrganizationVO, OrganizationType } from '@/typings/enterprise/organization';
import organizationService from '@/service/enterprise/organization';
import { copyToClipboard } from '@/utils';
import { useGlobalStore } from '@/store/global';
import NumberCodeInput from '@/components/NumberCodeInput';
import i18n from '@/i18n';

enum PageStep {
  Init,
  CreateOrgForm,
  CreateOrgSuccess,
  JoinOrgForm,
  JoinOrgSuccess,
}

enum ValidateStatus {
  success = 'success',
  error = 'error',
  validating = 'validating',
  none = 'none',
}
const formItemLayout = {
  labelCol: {
    span: 24, // Number of columns occupied by the label.
  },
  wrapperCol: {
    span: 24, // Number of columns occupied by the input.
  },
};

export interface CreateOrJoinOrgDialogProps extends ModalProps {}

const CreateOrJoinOrgDialog = ({ open }: CreateOrJoinOrgDialogProps) => {
  const [title, setTitle] = useState(i18n('team.createOrJoin.title'));
  const [titleDesc, setTitleDesc] = useState('');
  const [pageStep, setPageStep] = useState<PageStep>(PageStep.Init);
  const [inviteLink, setInviteLink] = useState(''); //
  const [applyOrg, setApplyOrg] = useState<IOrganizationVO | null>(null);

  const isValidateCode = useRef<ValidateStatus>(ValidateStatus.none);
  const newOrg = useRef<IOrganizationVO | null>(null);

  const [form] = Form.useForm();
  const { styles, cx } = useStyles();

  const { setCurOrg, createNewOrg, setOpenCreateOrJoinOrgDialog } = useOrgStore((s) => ({
    setCurOrg: s.setCurOrg,
    createNewOrg: s.createNewOrg,
    setOpenCreateOrJoinOrgDialog: s.setOpenCreateOrJoinOrgDialog,
  }));

  const { appConfig, appUrlConfig } = useGlobalStore((state) => ({
    appConfig: state.appConfig,
    appUrlConfig: state.appUrlConfig,
  }));

  const { isCN } = appConfig;
  const citiesData = useMemo(() => (!isCN ? abroadCity : domesticCity), []);
  const industries = useMemo(() => (!isCN ? abroadIndustries : domesticIndustries), []);
  const orgRole = useMemo(() => (!isCN ? abroadRoleCode : domesticRoleCode), []);

  useEffect(() => {
    switch (pageStep) {
      case PageStep.Init:
        setTitle(i18n('team.createOrJoin.title'));
        setTitleDesc('');
        break;
      case PageStep.CreateOrgForm:
        setTitle(i18n('team.create.title'));
        setTitleDesc(i18n('team.create.title.desc'));
        break;
      case PageStep.CreateOrgSuccess:
        setTitle(i18n('team.createSuccess.title'));
        setTitleDesc('');
        break;
      case PageStep.JoinOrgForm:
        setTitle(i18n('team.join.title'));
        setTitleDesc(i18n('team.join.title.desc'));
        break;
      case PageStep.JoinOrgSuccess:
        setTitle(i18n('team.joinSuccess.title'));
        setTitleDesc('');
        break;
      default:
        break;
    }
  }, [pageStep]);

  const renderContent = () => {
    switch (pageStep) {
      case PageStep.Init:
        return renderPageInit();
      case PageStep.CreateOrgForm:
        return renderCreateOrgForm();
      case PageStep.CreateOrgSuccess:
        return renderCreateOrgSuccess();
      case PageStep.JoinOrgForm:
        return renderJoinOrgForm();
      case PageStep.JoinOrgSuccess:
        return renderJoinOrgSuccess();
      default:
        return null;
    }
  };

  const renderPageInit = () => {
    return (
      <Flex vertical={true}>
        <div
          className={styles.initItem}
          onClick={() => {
            setPageStep(PageStep.CreateOrgForm);
          }}
        >
          <Flex align="center" gap={16}>
            <div className={styles.initItemIcon}>
              <IconfontSvg code="icon-a-xunwen1" size={24} />
            </div>
            <Flex vertical>
              <div className={styles.initItemTitle}>{i18n('team.create.title')}</div>
              <div className={styles.initItemDesc}>{i18n('team.create.title.desc')}</div>
            </Flex>
          </Flex>
          <ChevronRight className={styles.initItemArrow} size={14} />
        </div>

        <div
          className={styles.initItem}
          onClick={() => {
            setPageStep(PageStep.JoinOrgForm);
          }}
        >
          <Flex align="center" gap={16}>
            <div className={cx(styles.initItemIcon, styles.initItemIconSuccess)}>
              <IconfontSvg code="icon-a-xunwen1" size={24} />
            </div>
            <Flex vertical>
              <div className={styles.initItemTitle}>{i18n('team.join.title')}</div>
              <div className={styles.initItemDesc}>{i18n('team.join.title.desc')}</div>
            </Flex>
          </Flex>
          <ChevronRight className={styles.initItemArrow} size={14} />
        </div>
      </Flex>
    );
  };

  const renderCreateOrgForm = () => {
    return (
      <div>
        <Form {...formItemLayout} form={form} autoComplete="off">
          <Form.Item
            name="name"
            label={i18n('team.create.form.title')}
            rules={[{ required: true, message: i18n('team.create.form.title.placeholder') }]}
          >
            <Input placeholder={i18n('team.create.form.title.placeholder')} maxLength={50} />
          </Form.Item>
          <Form.Item
            name="area"
            label={i18n('team.create.form.area')}
            rules={[{ required: true, message: i18n('team.create.form.area') }]}
          >
            <Select placeholder={i18n('team.create.form.area')} showSearch>
              {citiesData.map((province) => (
                <Select.OptGroup key={province.region} label={province.region}>
                  {province.subregions.map((city) => (
                    <Select.Option key={city} value={city}>
                      {city}
                    </Select.Option>
                  ))}
                </Select.OptGroup>
              ))}
            </Select>
          </Form.Item>
          <Form.Item
            name="industry"
            label={i18n('team.create.form.industry')}
            rules={[{ required: true, message: i18n('team.create.form.industry.placeholder') }]}
          >
            <Select placeholder={i18n('team.create.form.industry.placeholder')} options={industries} />
          </Form.Item>
          <Form.Item
            name="roleList"
            label={i18n('team.create.form.role')}
            rules={[{ required: true, message: i18n('team.create.form.role.placeholder') }]}
          >
            <Select mode="multiple" placeholder={i18n('team.create.form.role.placeholder')} options={orgRole} />
          </Form.Item>
        </Form>

        <Flex style={{ opacity: 0.8 }} wrap="wrap" gap={4}>
          <div>{i18n('team.create.form.proxy')}</div>
          <a href={appUrlConfig.SERVICE_AGREEMENT} target="_blank" rel="noopener noreferrer">
            {i18n('team.create.form.proxy.service')}
          </a>
          、{/* {i18n('common.text.and')} */}
          <a href={appUrlConfig.PRIVACY_POLICY} target="_blank" rel="noopener noreferrer">
            {i18n('team.create.form.proxy.org')}
          </a>
        </Flex>
        <Flex justify="end" gap={8} style={{ marginTop: '64px' }}>
          <Button
            onClick={() => {
              setPageStep(PageStep.Init);
            }}
          >
            {i18n('common.button.cancel')}
          </Button>
          <Button
            type="primary"
            onClick={async () => {
              await form.validateFields();
              const formValue = form.getFieldsValue();

              const org = await createNewOrg({
                ...formValue,
                type: OrganizationType.TEAM,
              });
              newOrg.current = org;

              setInviteLink(
                `${appConfig.appUrl?.split('?')[0]}/invite?organizationCode=${
                  org.organizationCode
                }&roleCode=OPERATOR&inviterId=${org.createUserId}`,
              );
              setPageStep(PageStep.CreateOrgSuccess);
            }}
          >
            {i18n('common.title.create')}
          </Button>
        </Flex>
      </div>
    );
  };

  const renderCreateOrgSuccess = () => {
    return (
      <Flex vertical justify="center" align="center" gap={20}>
        <Empty
          image={EmptyImage.Chat}
          title={i18n('team.createSuccess.title2')}
          subTitle={i18n('team.createSuccess.title.desc')}
        />

        <div className={styles.inviteWrapper}>{inviteLink}</div>

        <Flex justify="end" gap={8} style={{ marginTop: '64px', width: '100%' }}>
          <Button
            type="primary"
            onClick={async () => {
              copyToClipboard(inviteLink);
            }}
          >
            {i18n('team.createSuccess.button')}
          </Button>
        </Flex>
      </Flex>
    );
  };

  const renderJoinOrgForm = () => {
    return (
      <Flex vertical>
        <Form {...formItemLayout} form={form} autoComplete="off">
          <Form.Item
            name={'name'}
            label={i18n('team.join.form.name')}
            rules={[{ required: true, message: i18n('team.join.form.name.placeholder') }]}
          >
            <Input placeholder={i18n('team.join.form.name.placeholder')} maxLength={50} />
          </Form.Item>
          <Form.Item
            name={'organizationCode'}
            label={i18n('team.join.form.code')}
            tooltip={<div>{i18n('team.join.form.code.placeholder')}</div>}
            extra={
              applyOrg ? (
                <span>
                  {i18n('team.join.form.code.tip')}:{applyOrg?.name}
                </span>
              ) : null
            }
            validateStatus={'success'} // Avoid the red outline without changing validation behavior.
            rules={[
              {
                validator: async (_, value) => {
                  if (value && value.length === 8) {
                    switch (isValidateCode.current) {
                      case ValidateStatus.success:
                        Promise;
                        break;
                      case ValidateStatus.error:
                        throw new Error(i18n('team.join.form.code.error'));
                      default:
                        throw new Error(i18n('team.join.form.code.verfying'));
                    }
                  }
                },
              },
            ]}
          >
            <NumberCodeInput
              onChange={(v) => {
                if (v.length !== 8) {
                  setApplyOrg(null);
                  isValidateCode.current = ValidateStatus.none;
                }
                // Clear field errors.
                form.setFields([
                  {
                    name: 'organizationCode',
                    errors: [],
                  },
                ]);
              }}
              onFinished={async (teamCode) => {
                isValidateCode.current = ValidateStatus.validating;
                try {
                  const res = await organizationService.queryOrgByTeamCode({ teamCode });
                  setApplyOrg(res);
                  isValidateCode.current = res ? ValidateStatus.success : ValidateStatus.error;
                } catch {
                  isValidateCode.current = ValidateStatus.error;
                }

                // Trigger validation manually.
                form.validateFields(['organizationCode']);
              }}
            />
          </Form.Item>
          <Form.Item name={'reason'} label={i18n('team.join.form.reason')}>
            <TextArea autoSize={{ minRows: 4, maxRows: 8 }} maxLength={200} />
          </Form.Item>
        </Form>

        <Flex justify="end" gap={8} style={{ marginTop: '64px' }}>
          <Button
            onClick={() => {
              setPageStep(PageStep.Init);
            }}
          >
            {i18n('common.button.cancel')}
          </Button>
          <Button
            type="primary"
            onClick={async () => {
              // Manually verify that organizationCode is eight characters long.
              if (form.getFieldValue('organizationCode').length !== 8) {
                form.setFields([
                  {
                    name: 'organizationCode',
                    errors: [i18n('team.join.form.code.error.count')],
                  },
                ]);
                return;
              }

              await form.validateFields();

              const formValue = form.getFieldsValue();
              await organizationService.joinOrganization(formValue);

              setPageStep(PageStep.JoinOrgSuccess);
            }}
          >
            {i18n('team.join.apply')}
          </Button>
        </Flex>
      </Flex>
    );
  };

  const renderJoinOrgSuccess = () => {
    return (
      <Flex vertical justify="center" align="center" gap={20}>
        <Flex vertical justify="center" align="center" gap={12}>
          <Empty
            image={EmptyImage.TeamApprove}
            title={i18n('team.joinSuccess.title2')}
            subTitle={i18n('team.joinSuccess.title.desc')}
          />
        </Flex>

        <Flex justify="end" gap={8} style={{ marginTop: '64px', width: '100%' }}>
          <Button
            type="primary"
            onClick={() => {
              setOpenCreateOrJoinOrgDialog(false);
            }}
          >
            {i18n('common.button.confirm')}
          </Button>
        </Flex>
      </Flex>
    );
  };

  return (
    <Modal
      open={open}
      title={title}
      titleDesc={titleDesc}
      headerBorder={true}
      footer={null}
      width={460}
      centered
      destroyOnClose
      maskClosable={false}
      onCancel={() => {
        setOpenCreateOrJoinOrgDialog(false);
        setPageStep(PageStep.Init);
        form.resetFields();

        if (newOrg.current) {
          setCurOrg(newOrg.current);
          newOrg.current = null;
        }
      }}
    >
      <div className={styles.wrapper}>{renderContent()}</div>
    </Modal>
  );
};

export default CreateOrJoinOrgDialog;
