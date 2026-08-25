import dataWikiService, {
  type DataWikiColumn,
  type DataWikiDefinition,
  type DataWikiDocumentBundle,
  type DataWikiResource,
} from '@/service/dataWiki';
import i18n from '@/i18n';
import connectionService from '@/service/connection';
import sqlService from '@/service/sql';
import type { IConnectionDetails } from '@/typings';
import feedback from '@/utils/feedback';
import {
  App,
  Button,
  Cascader,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { createStyles } from 'antd-style';
import { BookOpenText, Code, Database, Eye, FileText, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import {
  dataWikiSelectionsFromCascadeOptions,
  type DataWikiResourceSelection,
} from './dataWikiCascade';

interface Props {
  active: boolean;
  dataSources: IConnectionDetails[];
  onDirtyChange?: (dirty: boolean) => void;
}

interface DataWikiCascadeOption {
  value: string;
  label: string;
  kind: 'DATA_SOURCE' | 'DATABASE' | 'SCHEMA' | 'TABLE';
  isLeaf?: boolean;
  loading?: boolean;
  children?: DataWikiCascadeOption[];
  dataSourceId: number;
  dataSourceName?: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

const useDataWikiStyles = createStyles(({ css, token }) => ({
  page: css`
    display: flex;
    height: 100%;
    min-height: 0;
    background: ${token.colorBgLayout};
  `,
  rail: css`
    display: flex;
    width: 280px;
    min-width: 240px;
    flex-direction: column;
    border-right: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  railHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 14px 10px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,
  railTitle: css`
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
  `,
  railList: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 8px;
  `,
  wikiItem: css`
    cursor: pointer;
    border-radius: ${token.borderRadiusLG}px;
    padding: 10px !important;
    &[data-active='true'] {
      background: ${token.colorPrimaryBg};
    }
  `,
  wikiName: css`
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 500;
  `,
  wikiMeta: css`
    margin-top: 4px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  main: css`
    display: flex;
    min-width: 0;
    flex: 1;
    flex-direction: column;
  `,
  header: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 56px;
    padding: 0 18px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgContainer};
  `,
  headerCopy: css`
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 10px;
  `,
  headerIcon: css`
    display: grid;
    width: 32px;
    height: 32px;
    place-items: center;
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorPrimaryBg};
    color: ${token.colorPrimary};
  `,
  body: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 18px;
  `,
  overview: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
    gap: 12px;
    margin-bottom: 14px;
    @media (max-width: 900px) {
      grid-template-columns: 1fr;
    }
  `,
  panel: css`
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    background: ${token.colorBgContainer};
    padding: 14px;
  `,
  resourceTabs: css`
    min-height: 360px;
  `,
  markdownToolbar: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
  `,
  markdownWorkspace: css`
    display: grid;
    min-height: 420px;
    grid-template-columns: 250px minmax(0, 1fr);
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadiusLG}px;
    @media (max-width: 820px) {
      grid-template-columns: 190px minmax(0, 1fr);
    }
  `,
  documentRail: css`
    min-width: 0;
    border-right: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorFillQuaternary};
  `,
  documentRailTitle: css`
    display: flex;
    height: 40px;
    align-items: center;
    gap: 7px;
    padding: 0 12px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    color: ${token.colorTextSecondary};
    font-size: 12px;
    font-weight: 600;
  `,
  documentList: css`
    max-height: calc(100vh - 300px);
    overflow: auto;
    padding: 6px;
  `,
  documentItem: css`
    display: flex;
    width: 100%;
    align-items: center;
    gap: 7px;
    overflow: hidden;
    padding: 7px 8px;
    border: 0;
    border-radius: ${token.borderRadius}px;
    background: transparent;
    color: ${token.colorTextSecondary};
    cursor: pointer;
    font: inherit;
    text-align: left;
    &:hover { background: ${token.colorFillSecondary}; }
    &[data-active='true'] {
      background: ${token.colorPrimaryBg};
      color: ${token.colorPrimaryText};
    }
    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `,
  documentPane: css`
    min-width: 0;
    padding: 14px;
    background: ${token.colorBgContainer};
  `,
  markdownFrame: css`
    max-height: calc(100vh - 260px);
    overflow: auto;
    padding: 16px;
    background: ${token.colorBgContainer};
  `,
  wikiLocation: css`
    display: block;
    margin: -6px 0 12px;
    color: ${token.colorTextTertiary};
    font-size: 12px;
  `,
  markdownSource: css`
    margin: 0;
    font: 12px/1.65 ui-monospace, SFMono-Regular, Menlo, monospace;
    white-space: pre-wrap;
  `,
  markdownReview: css`
    color: ${token.colorText};
    line-height: 1.7;
    overflow-wrap: anywhere;
    h1,
    h2,
    h3 {
      margin: 22px 0 10px;
      line-height: 1.35;
    }
    h1 { margin-top: 0; font-size: 24px; }
    h2 {
      padding-bottom: 8px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      font-size: 18px;
    }
    h3 { font-size: 15px; }
    p { margin: 0 0 12px; }
    blockquote {
      margin: 14px 0;
      padding: 8px 14px;
      border-left: 3px solid ${token.colorPrimaryBorder};
      background: ${token.colorFillQuaternary};
      color: ${token.colorTextSecondary};
    }
    code {
      padding: 2px 5px;
      border-radius: ${token.borderRadiusSM}px;
      background: ${token.colorFillTertiary};
      font-family: ${token.fontFamilyCode};
      font-size: 0.9em;
    }
    pre {
      overflow: auto;
      padding: 12px;
      border-radius: ${token.borderRadius}px;
      background: ${token.colorFillTertiary};
      code { padding: 0; background: transparent; }
    }
    table {
      width: 100%;
      margin: 14px 0;
      border-collapse: collapse;
    }
    th,
    td {
      padding: 8px 10px;
      border: 1px solid ${token.colorBorderSecondary};
      text-align: left;
      vertical-align: top;
    }
    th { background: ${token.colorFillQuaternary}; }
    tr:nth-of-type(even) td { background: ${token.colorFillQuaternary}; }
  `,
  empty: css`
    display: grid;
    height: 100%;
    place-items: center;
  `,
}));

export default function DataWikiPage({ active, dataSources, onDirtyChange }: Props) {
  const { styles } = useDataWikiStyles();
  const { modal } = App.useApp();
  const [createForm] = Form.useForm();
  const [items, setItems] = useState<DataWikiDefinition[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [draft, setDraft] = useState<DataWikiDefinition>();
  const [selectedResourceId, setSelectedResourceId] = useState<string>();
  const [documentBundle, setDocumentBundle] = useState<DataWikiDocumentBundle>();
  const [selectedDocumentPath, setSelectedDocumentPath] = useState('README.md');
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [markdownView, setMarkdownView] = useState<'review' | 'source'>('review');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resourceModalOpen, setResourceModalOpen] = useState(false);
  const [resourceCascadeOptions, setResourceCascadeOptions] = useState<DataWikiCascadeOption[]>([]);
  const [selectedResources, setSelectedResources] = useState<DataWikiResourceSelection[]>([]);
  const onDirtyChangeRef = useRef(onDirtyChange);
  const preserveDraftAfterSaveRef = useRef<string>();
  const documentRequestRef = useRef(0);
  const selectedResource = draft?.resources.find((item) => item.id === selectedResourceId);
  const selectedDocument = documentBundle?.documents.find((item) => item.path === selectedDocumentPath);

  useEffect(() => {
    onDirtyChangeRef.current = onDirtyChange;
  }, [onDirtyChange]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await dataWikiService.list(undefined as void);
      setItems(result || []);
      setSelectedId((current) => (current && result?.some((item) => item.id === current) ? current : result?.[0]?.id));
    } catch {
      feedback.error(i18n('task.dataWiki.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadDocuments = useCallback(async (id: string) => {
    setDocumentsLoading(true);
    try {
      const bundle = await dataWikiService.documents({ id });
      setDocumentBundle(bundle);
      setSelectedDocumentPath((current) =>
        bundle.documents.some((document) => document.path === current) ? current : 'README.md',
      );
      return bundle;
    } catch {
      feedback.error(i18n('task.dataWiki.documentsLoadFailed'));
      return undefined;
    } finally {
      setDocumentsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (active) void load();
  }, [active, load]);
  useEffect(() => {
    documentRequestRef.current += 1;
    setDocumentBundle(undefined);
    setSelectedDocumentPath('README.md');
    if (selectedId) void loadDocuments(selectedId);
  }, [loadDocuments, selectedId]);
  useEffect(() => {
    if (selectedId && preserveDraftAfterSaveRef.current === selectedId) {
      preserveDraftAfterSaveRef.current = undefined;
      return;
    }
    const selected = items.find((item) => item.id === selectedId);
    setDraft(selected ? structuredClone(selected) : undefined);
    setSelectedResourceId(selected ? 'markdown' : undefined);
    onDirtyChangeRef.current?.(false);
  }, [items, selectedId]);

  const markDraft = (next: DataWikiDefinition) => {
    setDraft(next);
    onDirtyChange?.(true);
  };

  const createWiki = async () => {
    const values = await createForm.validateFields();
    setSaving(true);
    try {
      const created = await dataWikiService.create(values);
      createForm.resetFields();
      setItems((current) => [created, ...current]);
      setSelectedId(created.id);
      feedback.success(i18n('task.dataWiki.created'));
    } finally {
      setSaving(false);
    }
  };

  const openCreate = () =>
    modal.confirm({
      title: i18n('task.dataWiki.createTitle'),
      content: (
        <Form form={createForm} layout="vertical">
          <Form.Item name="name" label={i18n('task.dataWiki.name')} rules={[{ required: true }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="description" label={i18n('task.dataWiki.description')}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      ),
      okText: i18n('task.dataWiki.create'),
      cancelText: i18n('task.action.cancel'),
      onOk: createWiki,
    });

  const save = async () => {
    if (!draft) return undefined;
    setSaving(true);
    try {
      const updated = await dataWikiService.update({ ...draft, expectedRevision: draft.revision });
      preserveDraftAfterSaveRef.current = updated.id;
      setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setDraft(updated);
      onDirtyChange?.(false);
      feedback.success(i18n('task.dataWiki.saved'));
      return updated;
    } finally {
      setSaving(false);
    }
  };

  const deleteWiki = async () => {
    if (!draft) return;
    await dataWikiService.remove({ id: draft.id, expectedRevision: draft.revision });
    setItems((current) => current.filter((item) => item.id !== draft.id));
    setSelectedId(undefined);
    feedback.success(i18n('task.dataWiki.deleted'));
  };

  const tableOptions = async (option: DataWikiCascadeOption) => {
    const result = await sqlService.getTableList({
      dataSourceId: option.dataSourceId,
      databaseName: option.databaseName,
      schemaName: option.schemaName,
      pageNo: 1,
      pageSize: 1000,
    });
    return (result.data || []).map<DataWikiCascadeOption>((table) => ({
      value: `table:${table.name}`,
      label: table.name,
      kind: 'TABLE',
      isLeaf: true,
      dataSourceId: option.dataSourceId,
      dataSourceName: option.dataSourceName,
      databaseName: option.databaseName,
      schemaName: option.schemaName,
      tableName: table.name,
    }));
  };

  const schemaOrTableOptions = async (option: DataWikiCascadeOption) => {
    const schemas = await connectionService
      .getSchemaList({
        dataSourceId: option.dataSourceId,
        databaseName: option.databaseName,
        refresh: false,
      })
      .catch(() => []);
    if (schemas?.length) {
      return schemas.map<DataWikiCascadeOption>((schema) => ({
        value: `schema:${schema.name}`,
        label: schema.name,
        kind: 'SCHEMA',
        isLeaf: false,
        dataSourceId: option.dataSourceId,
        dataSourceName: option.dataSourceName,
        databaseName: option.databaseName,
        schemaName: schema.name,
      }));
    }
    return tableOptions(option);
  };

  const loadResourceCascade = async (selectedOptions: DataWikiCascadeOption[]) => {
    const target = selectedOptions[selectedOptions.length - 1];
    if (!target || target.kind === 'TABLE') return;
    target.loading = true;
    setResourceCascadeOptions((current) => [...current]);
    try {
      if (target.kind === 'DATA_SOURCE') {
        const databases = await connectionService
          .getDatabaseList({ dataSourceId: target.dataSourceId, refresh: false })
          .catch(() => []);
        target.children = databases?.length
          ? databases.map<DataWikiCascadeOption>((database) => ({
              value: `database:${database.name}`,
              label: database.name,
              kind: 'DATABASE',
              isLeaf: false,
              dataSourceId: target.dataSourceId,
              dataSourceName: target.dataSourceName,
              databaseName: database.name,
            }))
          : await schemaOrTableOptions(target);
      } else if (target.kind === 'DATABASE') {
        target.children = await schemaOrTableOptions(target);
      } else {
        target.children = await tableOptions(target);
      }
    } catch {
      target.children = [];
      feedback.error(i18n('task.dataWiki.cascadeLoadFailed'));
    } finally {
      target.loading = false;
      setResourceCascadeOptions((current) => [...current]);
    }
  };

  const openResourceModal = () => {
    setResourceCascadeOptions(
      dataSources.map((source) => ({
        value: `datasource:${source.id}`,
        label: source.alias || String(source.id),
        kind: 'DATA_SOURCE',
        isLeaf: false,
        dataSourceId: source.id,
        dataSourceName: source.alias,
      })),
    );
    setSelectedResources([]);
    setResourceModalOpen(true);
  };

  const addResource = async () => {
    if (!draft) return;
    if (!selectedResources.length) {
      feedback.error(i18n('task.dataWiki.selectionRequired'));
      return;
    }
    const uniqueSelections = selectedResources.filter(
      (selection, index, all) =>
        all.findIndex(
          (item) =>
            item.dataSourceId === selection.dataSourceId &&
            item.databaseName === selection.databaseName &&
            item.schemaName === selection.schemaName &&
            item.tableName === selection.tableName,
        ) === index,
    );
    if (
      uniqueSelections.some((selection) =>
        draft.resources.some(
          (item) =>
            item.dataSourceId === selection.dataSourceId &&
            item.databaseName === selection.databaseName &&
            item.schemaName === selection.schemaName &&
            item.tableName === selection.tableName,
        ),
      )
    ) {
      feedback.error(i18n('task.dataWiki.resourceDuplicate'));
      return;
    }
    setSaving(true);
    try {
      const resources = await Promise.all(
        uniqueSelections.map(async (selection): Promise<DataWikiResource> => {
          const details = await sqlService.getTableDetails({ ...selection, refresh: false });
          return {
            id: crypto.randomUUID(),
            ...selection,
            tableType: 'TABLE',
            sourceComment: details.comment || undefined,
            columns: (details.columnList || []).map((column) => ({
              name: column.name || '',
              dataType: column.columnType || column.typeName || undefined,
              sourceComment: column.comment || undefined,
            })),
          };
        }),
      );
      markDraft({ ...draft, resources: [...draft.resources, ...resources] });
      setSelectedResourceId(resources[0]?.id);
      setResourceModalOpen(false);
      setSelectedResources([]);
    } catch {
      feedback.error(i18n('task.dataWiki.tableReadFailed'));
    } finally {
      setSaving(false);
    }
  };

  const updateResource = (patch: Partial<DataWikiResource>) => {
    if (!draft || !selectedResource) return;
    markDraft({
      ...draft,
      resources: draft.resources.map((item) => (item.id === selectedResource.id ? { ...item, ...patch } : item)),
    });
  };

  const updateColumn = (name: string, patch: Partial<DataWikiColumn>) => {
    if (!selectedResource) return;
    updateResource({
      columns: selectedResource.columns.map((column) => (column.name === name ? { ...column, ...patch } : column)),
    });
  };

  const previewMarkdown = async () => {
    if (!draft) return;
    const updated = await save();
    if (!updated) return;
    await loadDocuments(updated.id);
    setMarkdownView('review');
    setSelectedResourceId('markdown');
  };

  const openDocument = async (path: string) => {
    setSelectedDocumentPath(path);
    const document = documentBundle?.documents.find((item) => item.path === path);
    if (!selectedId || !document || document.content !== undefined) return;
    const requestId = ++documentRequestRef.current;
    setDocumentsLoading(true);
    try {
      const content = await dataWikiService.documentContent({ id: selectedId, path });
      if (requestId !== documentRequestRef.current) return;
      setDocumentBundle((current) =>
        current
          ? {
              ...current,
              documents: current.documents.map((item) => ({
                ...item,
                content: item.kind === 'README' ? item.content : item.path === path ? content : undefined,
              })),
            }
          : current,
      );
    } catch {
      feedback.error(i18n('task.dataWiki.documentsLoadFailed'));
    } finally {
      if (requestId === documentRequestRef.current) setDocumentsLoading(false);
    }
  };

  return (
    <div className={styles.page}>
      <aside className={styles.rail}>
        <div className={styles.railHeader}>
          <span className={styles.railTitle}>
            <BookOpenText size={17} />
            DataWiki
          </span>
          <Tooltip title={i18n('task.dataWiki.createTitle')}>
            <Button type="text" icon={<Plus size={16} />} onClick={openCreate} />
          </Tooltip>
        </div>
        <div className={styles.railList}>
          <Spin spinning={loading}>
            <List
              dataSource={items}
              locale={{ emptyText: i18n('task.dataWiki.empty') }}
              renderItem={(item) => (
                <List.Item
                  className={styles.wikiItem}
                  data-active={item.id === selectedId}
                  onClick={() => setSelectedId(item.id)}
                >
                  <div>
                    <div className={styles.wikiName}>{item.name}</div>
                    <div className={styles.wikiMeta}>
                      {i18n('task.dataWiki.tableCount', item.resources.length)} · rev {item.revision}
                    </div>
                  </div>
                </List.Item>
              )}
            />
          </Spin>
        </div>
      </aside>
      <main className={styles.main}>
        {draft ? (
          <>
            <header className={styles.header}>
              <div className={styles.headerCopy}>
                <span className={styles.headerIcon}>
                  <BookOpenText size={17} />
                </span>
                <div>
                  <strong>{draft.name}</strong>
                  <div className={styles.wikiMeta}>{i18n('task.dataWiki.hint')}</div>
                </div>
              </div>
              <Space>
                <Tooltip title={i18n('task.action.refresh')}>
                  <Button icon={<RefreshCw size={15} />} onClick={() => void load()} />
                </Tooltip>
                <Popconfirm title={i18n('task.dataWiki.deleteConfirm')} onConfirm={() => void deleteWiki()}>
                  <Tooltip title={i18n('task.dataWiki.delete')}>
                    <Button danger icon={<Trash2 size={15} />} />
                  </Tooltip>
                </Popconfirm>
                <Button type="primary" icon={<Save size={15} />} loading={saving} onClick={() => void save()}>
                  {i18n('task.dataWiki.save')}
                </Button>
              </Space>
            </header>
            <div className={styles.body}>
              <div className={styles.overview}>
                <section className={styles.panel}>
                  <Form layout="vertical">
                    <Form.Item label={i18n('task.dataWiki.name')}>
                      <Input
                        value={draft.name}
                        onChange={(event) => markDraft({ ...draft, name: event.target.value })}
                      />
                    </Form.Item>
                    <Form.Item label={i18n('task.dataWiki.description')} style={{ marginBottom: 0 }}>
                      <Input.TextArea
                        rows={4}
                        value={draft.description}
                        onChange={(event) => markDraft({ ...draft, description: event.target.value })}
                      />
                    </Form.Item>
                  </Form>
                </section>
                <section className={styles.panel}>
                  <strong>{i18n('task.dataWiki.scope')}</strong>
                  <p className={styles.wikiMeta}>{i18n('task.dataWiki.scopeHint')}</p>
                  <Space wrap>
                    {draft.resources.map((resource) => (
                      <Tag
                        key={resource.id}
                        icon={<Database size={12} />}
                        closable
                        onClose={(event) => {
                          event.preventDefault();
                          markDraft({ ...draft, resources: draft.resources.filter((item) => item.id !== resource.id) });
                        }}
                      >
                        {resource.dataSourceName || resource.dataSourceId} / {resource.tableName}
                      </Tag>
                    ))}
                    <Button size="small" icon={<Plus size={13} />} onClick={openResourceModal}>
                      {i18n('task.dataWiki.bindTable')}
                    </Button>
                  </Space>
                </section>
              </div>
              <section className={styles.panel}>
                <Tabs
                  className={styles.resourceTabs}
                  activeKey={selectedResourceId || 'markdown'}
                  onChange={setSelectedResourceId}
                  items={[
                    ...draft.resources.map((resource) => ({
                      key: resource.id,
                      label: resource.businessName || resource.tableName,
                      children:
                        resource.id === selectedResourceId ? (
                          <>
                            <Form layout="vertical">
                              <Space style={{ width: '100%' }} align="start">
                                <Form.Item label={i18n('task.dataWiki.businessName')}>
                                  <Input
                                    value={resource.businessName}
                                    onChange={(event) => updateResource({ businessName: event.target.value })}
                                  />
                                </Form.Item>
                                <Form.Item label={i18n('task.dataWiki.tablePurpose')} style={{ minWidth: 360 }}>
                                  <Input
                                    value={resource.businessDescription}
                                    onChange={(event) => updateResource({ businessDescription: event.target.value })}
                                  />
                                </Form.Item>
                              </Space>
                            </Form>
                            <Table
                              rowKey="name"
                              size="small"
                              pagination={false}
                              scroll={{ x: 900 }}
                              dataSource={resource.columns}
                              columns={[
                                { title: i18n('task.dataWiki.column'), dataIndex: 'name', width: 160 },
                                { title: i18n('task.dataWiki.type'), dataIndex: 'dataType', width: 130 },
                                { title: i18n('task.dataWiki.sourceComment'), dataIndex: 'sourceComment', width: 180 },
                                {
                                  title: i18n('task.dataWiki.businessName'),
                                  dataIndex: 'businessName',
                                  width: 160,
                                  render: (value, row) => (
                                    <Input
                                      value={value}
                                      onChange={(event) => updateColumn(row.name, { businessName: event.target.value })}
                                    />
                                  ),
                                },
                                {
                                  title: i18n('task.dataWiki.businessMeaning'),
                                  dataIndex: 'businessDescription',
                                  width: 240,
                                  render: (value, row) => (
                                    <Input
                                      value={value}
                                      onChange={(event) =>
                                        updateColumn(row.name, { businessDescription: event.target.value })
                                      }
                                    />
                                  ),
                                },
                                {
                                  title: i18n('task.dataWiki.sampleValues'),
                                  dataIndex: 'sampleValues',
                                  width: 180,
                                  render: (value, row) => (
                                    <Input
                                      value={value}
                                      onChange={(event) => updateColumn(row.name, { sampleValues: event.target.value })}
                                    />
                                  ),
                                },
                                {
                                  title: i18n('task.dataWiki.enumDescription'),
                                  dataIndex: 'enumDescription',
                                  width: 180,
                                  render: (value, row) => (
                                    <Input
                                      value={value}
                                      onChange={(event) =>
                                        updateColumn(row.name, { enumDescription: event.target.value })
                                      }
                                    />
                                  ),
                                },
                              ]}
                            />
                          </>
                        ) : null,
                    })),
                    {
                      key: 'markdown',
                      label: (
                        <span>
                          <FileText size={13} /> Markdown
                        </span>
                      ),
                      children: (
                        <>
                          <div className={styles.markdownWorkspace}>
                            <aside className={styles.documentRail}>
                              <div className={styles.documentRailTitle}>
                                <BookOpenText size={14} />
                                {i18n('task.dataWiki.documents')}
                              </div>
                              <div className={styles.documentList}>
                                {(documentBundle?.documents || []).map((document) => (
                                  <button
                                    key={document.path}
                                    type="button"
                                    className={styles.documentItem}
                                    data-active={document.path === selectedDocumentPath}
                                    title={document.path}
                                    onClick={() => void openDocument(document.path)}
                                  >
                                    {document.kind === 'README' ? <BookOpenText size={14} /> : <FileText size={14} />}
                                    <span>{document.kind === 'README' ? 'README.md' : document.title}</span>
                                  </button>
                                ))}
                              </div>
                            </aside>
                            <section className={styles.documentPane}>
                              <div className={styles.markdownToolbar}>
                                <Button icon={<FileText size={14} />} onClick={() => void previewMarkdown()}>
                                  {i18n('task.dataWiki.syncWiki')}
                                </Button>
                                <Segmented
                                  value={markdownView}
                                  onChange={(value) => setMarkdownView(value as 'review' | 'source')}
                                  options={[
                                    {
                                      value: 'review',
                                      label: i18n('task.dataWiki.markdownReview'),
                                      icon: <Eye size={14} />,
                                    },
                                    {
                                      value: 'source',
                                      label: i18n('task.dataWiki.markdownSource'),
                                      icon: <Code size={14} />,
                                    },
                                  ]}
                                />
                              </div>
                              {documentBundle?.rootDirectory && (
                                <Typography.Text
                                  className={styles.wikiLocation}
                                  copyable={{ text: documentBundle.rootDirectory }}
                                >
                                  {i18n('task.dataWiki.localDirectory')}: {documentBundle.rootDirectory}
                                </Typography.Text>
                              )}
                              <Spin spinning={documentsLoading}>
                                <div className={styles.markdownFrame}>
                                  {selectedDocument?.content ? (
                                    markdownView === 'review' ? (
                                      <article className={styles.markdownReview}>
                                        <ReactMarkdown
                                          remarkPlugins={[remarkGfm]}
                                          components={{
                                            a: ({ href, children }) => {
                                              const target = documentBundle?.documents.find(
                                                (document) => document.path === href,
                                              );
                                              return target ? (
                                                <a
                                                  href={href}
                                                  onClick={(event) => {
                                                    event.preventDefault();
                                                    void openDocument(target.path);
                                                  }}
                                                >
                                                  {children}
                                                </a>
                                              ) : (
                                                <a href={href} target="_blank" rel="noreferrer noopener">
                                                  {children}
                                                </a>
                                              );
                                            },
                                          }}
                                        >
                                          {selectedDocument.content}
                                        </ReactMarkdown>
                                      </article>
                                    ) : (
                                      <pre className={styles.markdownSource}>{selectedDocument.content}</pre>
                                    )
                                  ) : (
                                    <Empty
                                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                                      description={i18n('task.dataWiki.markdownPlaceholder')}
                                    />
                                  )}
                                </div>
                              </Spin>
                            </section>
                          </div>
                        </>
                      ),
                    },
                  ].sort((left, right) => {
                    if (left.key === 'markdown') return -1;
                    if (right.key === 'markdown') return 1;
                    return 0;
                  })}
                />
              </section>
            </div>
          </>
        ) : (
          <div className={styles.empty}>
            <Empty description={i18n('task.dataWiki.emptyDescription')}>
              <Button type="primary" icon={<Plus size={15} />} onClick={openCreate}>
                {i18n('task.dataWiki.createTitle')}
              </Button>
            </Empty>
          </div>
        )}
      </main>
      <Modal
        title={i18n('task.dataWiki.bindTitle')}
        open={resourceModalOpen}
        confirmLoading={saving}
        onCancel={() => {
          setResourceModalOpen(false);
          setSelectedResources([]);
        }}
        onOk={() => void addResource()}
        destroyOnClose
      >
        <p className={styles.wikiMeta}>{i18n('task.dataWiki.bindHint')}</p>
        <Cascader<DataWikiCascadeOption>
          multiple
          showCheckedStrategy={Cascader.SHOW_CHILD}
          maxTagCount="responsive"
          style={{ width: '100%' }}
          options={resourceCascadeOptions}
          loadData={(options) => void loadResourceCascade(options as DataWikiCascadeOption[])}
          placeholder={i18n('task.dataWiki.cascadePlaceholder')}
          displayRender={(labels) => labels.join(' / ')}
          onChange={(value, selectedOptions) =>
            setSelectedResources(dataWikiSelectionsFromCascadeOptions(selectedOptions, value, dataSources))
          }
        />
      </Modal>
    </div>
  );
}
