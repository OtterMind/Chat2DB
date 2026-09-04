import { useCallback, useEffect, useState } from 'react';
import { Button, Form, Modal, Select, message } from 'antd';
import i18n from '@/i18n';
import sqlService from '@/service/sql';

type OptionItem = {
  value: string;
  label: string;
  charset?: string | null;
};

/**
 * Database default character set and collation editor (MYSQL-OBJ-001). Loads the values
 * reported by the server, filters collations by the selected character set, previews the
 * resulting ALTER DATABASE, executes it, and reloads. Unchanged values run no DDL.
 */
const DatabasePropertiesContent = ({
  dataSourceId,
  databaseName,
}: {
  dataSourceId: number;
  databaseName: string;
}) => {
  const [options, setOptions] = useState<{ charsets: OptionItem[]; collations: OptionItem[] }>({
    charsets: [],
    collations: [],
  });
  const [current, setCurrent] = useState<{ charset: string | null; collation: string | null } | null>(null);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm<{ charset?: string; collation?: string }>();
  const charset = Form.useWatch('charset', form);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      sqlService.getDatabaseFieldTypeList({ dataSourceId, databaseName }),
      sqlService.getDatabaseInfo({ dataSourceId, databaseName }),
    ])
      .then(([field, info]) => {
        setOptions({
          charsets: (field?.charsets || []).map((i) => ({ value: i.charsetName, label: i.charsetName })),
          collations: (field?.collations || []).map((i) => ({
            value: i.collationName,
            label: i.collationName,
            charset: i.charset,
          })),
        });
        setCurrent(info || null);
        form.setFieldsValue({
          charset: info?.charset ?? undefined,
          collation: info?.collation ?? undefined,
        });
      })
      .catch((e) => message.error(e?.message || i18n('common.text.failure')))
      .finally(() => setLoading(false));
  }, [dataSourceId, databaseName, form]);

  useEffect(() => {
    load();
  }, [load]);

  const filteredCollations = useCallback(
    (selectedCharset?: string) => {
      if (!selectedCharset) {
        return options.collations;
      }
      return options.collations.filter((c) => !c.charset || c.charset === selectedCharset);
    },
    [options.collations],
  );

  const save = () => {
    form.validateFields().then((values) => {
      const unchanged =
        current &&
        (values.charset ?? null) === current.charset &&
        (values.collation ?? null) === current.collation;
      if (unchanged) {
        message.info(i18n('workspace.ops.noChange'));
        return;
      }
      sqlService
        .previewAlterDatabaseSql({ dataSourceId, databaseName, charset: values.charset, collation: values.collation })
        .then((sql) => {
          if (!sql) {
            message.info(i18n('workspace.ops.noChange'));
            return;
          }
          Modal.confirm({
            title: i18n('workspace.ops.previewSql'),
            content: <pre style={{ whiteSpace: 'pre-wrap' }}>{sql}</pre>,
            okText: i18n('common.button.execute'),
            cancelText: i18n('common.button.cancel'),
            onOk: () =>
              sqlService
                .executeDDL({ dataSourceId, sql })
                .then(() => {
                  message.success(i18n('common.tips.saveSuccessfully'));
                  load();
                })
                .catch((e) => message.error(e?.message || i18n('common.text.failure'))),
          });
        })
        .catch((e) => message.error(e?.message || i18n('common.text.failure')));
    });
  };

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span>{i18n('workspace.ops.charsetHint')}</span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
      </div>
      <Form form={form} layout="vertical">
        <Form.Item label={i18n('workspace.ops.charset')} name="charset">
          <Select
            options={options.charsets}
            showSearch
            optionFilterProp="label"
            onChange={() => form.setFieldsValue({ collation: undefined })}
          />
        </Form.Item>
        <Form.Item label={i18n('workspace.ops.collation')} name="collation">
          <Select options={filteredCollations(charset)} showSearch optionFilterProp="label" />
        </Form.Item>
      </Form>
      <Button type="primary" onClick={save} loading={loading}>
        {i18n('common.button.save')}
      </Button>
    </div>
  );
};

export default DatabasePropertiesContent;
