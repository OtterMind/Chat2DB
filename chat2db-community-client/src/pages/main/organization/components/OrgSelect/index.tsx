import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useOrgStore } from '@/store/organization';
import { IOrganizationVO, OrganizationType } from '@/typings/enterprise/organization';
import { Dropdown, Flex } from 'antd';
import Avatar from '@/components/Avatar';
import { useStyles } from './style';

import { IconfontSvg } from '@chat2db/ui';
import i18n from '@/i18n';

const OrgSelect = () => {
  const { styles, cx } = useStyles();
  const { curOrg, orgList, setCurOrg, setOpenCreateOrJoinOrgDialog } = useOrgStore((state) => ({
    curOrg: state.curOrg,
    orgList: state.orgList,
    setCurOrg: state.setCurOrg,
    setOpenCreateOrJoinOrgDialog: state.setOpenCreateOrJoinOrgDialog,
  }));

  const [openDropdown, setOpenDropdown] = useState<boolean>(false);

  const renderItem = (org: IOrganizationVO, checked?: boolean, isBar?: boolean) => {
    return (
      <div className={cx(styles.item, !isBar && styles.dropItem, !isBar && checked && styles.dropItemChecked)}>
        <div className={styles.itemLeft}>
          <Avatar org={org} size={isBar ? 24 : 32} />
          <div className={styles.itemName}>{org.name}</div>
          <div className={styles.itemTag}>Free</div>
        </div>
        {!!checked && <div>Y</div>}
        {isBar && <ChevronDown size={14} />}
      </div>
    );
  };

  const renderDropdown = () => {
    return (
      <div className={styles.dropdownWrapper}>
        <div className={styles.dropdownTitle}>请选择团队</div>
        {renderMenu()}
        <div className={styles.divide} />
        <div
          className={styles.dropdownCreate}
          onClick={() => {
            setOpenCreateOrJoinOrgDialog(true);
            setOpenDropdown(false);
          }}
        >
          <Flex justify="space-between" align="center" gap={12}>
            <div className={styles.dropdownCreateIcon}>
              <IconfontSvg code="icon-a-xunwen1" size={20} />
            </div>
            {i18n('setting.nav.createOrgJoinOrg')}
          </Flex>

          <ChevronRight size={14} />
        </div>
      </div>
    );
  };

  const renderMenu = () => {
    const teamList = orgList?.filter((org) => org.type === OrganizationType.TEAM);
    return (teamList || []).map((org, idx) => {
      return (
        <div
          key={idx}
          onClick={() => {
            setCurOrg(org);
            setOpenDropdown(false);
          }}
        >
          {renderItem(org, curOrg?.id === org.id, false)}
        </div>
      );
    });
  };

  if (!curOrg) {
    return null;
  }

  return (
    <div className={styles.wrapper}>
      <Dropdown
        open={openDropdown}
        dropdownRender={renderDropdown}
        trigger={['click']}
        placement={'bottom'}
        destroyPopupOnHide
        onOpenChange={(e) => setOpenDropdown(e)}
      >
        {renderItem(curOrg, false, true)}
      </Dropdown>
    </div>
  );
};

export default OrgSelect;
