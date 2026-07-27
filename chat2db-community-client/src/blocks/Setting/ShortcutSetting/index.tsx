import { useMemo, useState } from 'react';
import { Button, Table, Tag, type TableProps } from 'antd';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { staticMessage } from '@chat2db/ui';
import { useStyles } from './style';
import { useGlobalStore } from '@/store/global';
import SettingSubsection from '../SettingSubsection';
import ShortcutInput from './ShortcutInput';
import { i18n } from '@/i18n';
import {
  EffectiveShortcutConfig,
  ShortcutAction,
  ShortcutOverrides,
  ShortcutScope,
  getEffectiveShortcutConfigMap,
  isShortcutBindingEqual,
} from '@/constants/shortcut';

type ShortcutTableRecord = EffectiveShortcutConfig & {
  labelText: string;
};

type I18nKey = Parameters<typeof i18n>[0];

const SHORTCUT_SCOPE_GROUPS: Array<{
  scope: ShortcutScope;
  title: I18nKey;
}> = [
  {
    scope: ShortcutScope.Global,
    title: 'setting.shortcut.group.global',
  },
  {
    scope: ShortcutScope.Workspace,
    title: 'setting.shortcut.group.workspace',
  },
  {
    scope: ShortcutScope.LocalSqlFileTree,
    title: 'setting.shortcut.group.localSqlFileTree',
  },
  {
    scope: ShortcutScope.SqlEditor,
    title: 'setting.shortcut.group.sqlEditor',
  },
  {
    scope: ShortcutScope.ResultSet,
    title: 'setting.shortcut.group.resultSet',
  },
  {
    scope: ShortcutScope.Table,
    title: 'setting.shortcut.group.table',
  },
];

export default function ShortcutSetting() {
  const { styles } = useStyles();
  const [collapsedScopes, setCollapsedScopes] = useState<Partial<Record<ShortcutScope, boolean>>>({});
  const { shortcutOverrides, updateShortcutConfig, resetShortcutConfig, resetAllShortcutConfig } = useGlobalStore(
    (state) => ({
      shortcutOverrides: state.shortcutOverrides,
      updateShortcutConfig: state.updateShortcutConfig,
      resetShortcutConfig: state.resetShortcutConfig,
      resetAllShortcutConfig: state.resetAllShortcutConfig,
    }),
  );
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  const dataSource = useMemo(
    () =>
      Object.values(ShortcutAction).map((action) => {
        const item = shortcutConfig[action];
        return {
          ...item,
          labelText: i18n(item.label as I18nKey),
        };
      }),
    [shortcutConfig],
  );

  const groupedDataSource = useMemo(
    () =>
      SHORTCUT_SCOPE_GROUPS.map((group) => ({
        ...group,
        dataSource: dataSource.filter((item) => item.scope === group.scope),
      })),
    [dataSource],
  );

  const validateShortcut = (record: ShortcutTableRecord, newValue: string) => {
    const conflictConfig = dataSource.find(
      (item) =>
        item.key !== record.key &&
        item.scope === record.scope &&
        item.binding &&
        isShortcutBindingEqual(item.binding, newValue),
    );

    if (conflictConfig) {
      staticMessage.warning(i18n('setting.shortcut.conflict', conflictConfig.labelText));
      return false;
    }

    return true;
  };

  const toggleGroup = (scope: ShortcutScope) => {
    setCollapsedScopes((prev) => ({
      ...prev,
      [scope]: !prev[scope],
    }));
  };

  const columns: TableProps<ShortcutTableRecord>['columns'] = [
    {
      title: i18n('setting.shortcut.table.command'),
      dataIndex: 'labelText',
      key: 'label',
    },
    {
      title: i18n('setting.shortcut.table.shortcut'),
      dataIndex: 'key',
      key: 'shortcut',
      width: 240,
      render: (_key: ShortcutAction, record: ShortcutTableRecord) => (
        <ShortcutInput
          value={record.binding}
          disabled={!record.canModify}
          placeholder={record.disabled ? i18n('setting.shortcut.placeholder.disabled') : undefined}
          onChange={(newValue) => {
            if (!validateShortcut(record, newValue)) {
              return;
            }
            updateShortcutConfig(record.key, newValue);
          }}
        />
      ),
    },
    {
      title: i18n('setting.shortcut.table.status'),
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (_: any, record: ShortcutTableRecord) => {
        if (record.disabled) {
          return <Tag>{i18n('setting.shortcut.status.disabled')}</Tag>;
        }
        if (record.isDefault) {
          return <Tag color="blue">{i18n('setting.shortcut.status.default')}</Tag>;
        }
        return <Tag color="green">{i18n('setting.shortcut.status.custom')}</Tag>;
      },
    },
    {
      title: i18n('setting.shortcut.table.action'),
      key: 'action',
      width: 100,
      render: (_: any, record: ShortcutTableRecord) => (
        <div className={styles.actionGroup}>
          <Button
            size="small"
            disabled={record.isDefault}
            onClick={() => {
              resetShortcutConfig(record.key);
            }}
          >
            {i18n('setting.shortcut.table.reset')}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className={styles.container}>
      <SettingSubsection
        className={styles.subsectionWithoutDivider}
        title={i18n('setting.nav.shortcut')}
        describe={i18n('setting.nav.shortcutDescribe')}
      />
      <div className={styles.toolbar}>
        <Button onClick={resetAllShortcutConfig}>{i18n('setting.shortcut.table.resetAll')}</Button>
      </div>
      <div className={styles.groupList}>
        {groupedDataSource.map((group) => {
          if (!group.dataSource.length) {
            return null;
          }

          const collapsed = !!collapsedScopes[group.scope];

          return (
            <section key={group.scope} className={styles.groupSection}>
              <Button type="text" className={styles.groupHeader} onClick={() => toggleGroup(group.scope)}>
                <span className={styles.groupArrow}>
                  {collapsed ? <ChevronRight size={14} /> : <ChevronDown size={14} />}
                </span>
                <span className={styles.groupTitle}>{i18n(group.title)}</span>
              </Button>
              {!collapsed && (
                <Table
                  size="small"
                  dataSource={group.dataSource}
                  columns={columns}
                  pagination={false}
                  rowKey="key"
                  className={styles.tableWrapper}
                />
              )}
            </section>
          );
        })}
      </div>
    </div>
  );
}
