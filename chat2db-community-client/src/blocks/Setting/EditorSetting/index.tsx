import { useMemo } from 'react';
import i18n from '@/i18n';
import { useStyles } from './style';
import SettingSubsection from '../SettingSubsection';
import { useCommonStyle } from '../commonStyle';
import { Form, InputNumber, Radio, Col, Row, Input, Select, Switch } from 'antd';
import { useGlobalStore } from '@/store/global';

import { DEFAULT_EDITOR_SETTINGS, MonacoEditor, editorFontFamily, editorThemes } from '@/components/SQLEditor';
import exampleSQL from '@/components/SQLEditor/data/example.sql';
import InteractiveSelect from './InteractiveSelect';
import { osNow } from '@/utils';
import { v4 as uuid } from 'uuid';
import { databaseMap } from '@/constants';
import { useUpdateEffect } from 'ahooks';
import { ChevronDown } from 'lucide-react';

const THEMES = Object.entries(editorThemes).map(([key]) => ({ label: key, value: key }));
const FONT_FAMILIES = Object.entries(editorFontFamily).map(([key, value]) => ({ label: key, value }));
const FONT_FAMILIES_WINDOW = Object.entries(editorFontFamily)
  .filter(([key]) => key !== 'JetBrains Mono')
  .map(([key, value]) => ({ label: key, value }));
const { isMac, isWin } = osNow();

function EditorSettings() {
  const { styles, theme } = useStyles();
  const { appearance } = theme;
  const { styles: commonStyles } = useCommonStyle();
  const [form] = Form.useForm();
  const { updateEditorSettings, _editorSettings, getEditorTheme } = useGlobalStore((s) => ({
    _editorSettings: s.editorSettings,
    updateEditorSettings: s.updateEditorSettings,
    getEditorTheme: s.getEditorTheme,
  }));

  const editorSettings = {
    ..._editorSettings,
    theme: getEditorTheme(appearance),
  };

  const fontFamilies = useMemo(() => (isWin ? FONT_FAMILIES_WINDOW : FONT_FAMILIES), [isWin]);
  const ddlClickModifierKey = isMac ? 'Cmd' : 'Ctrl';
  const ddlTriggerOptions = useMemo(
    () => [
      {
        label: i18n('monaco.tableDDLTriggerMode.hover'),
        value: 'hover',
      },
      {
        label: i18n('monaco.tableDDLTriggerMode.click', ddlClickModifierKey),
        value: 'click',
      },
    ],
    [ddlClickModifierKey],
  );

  const handleValuesChange = (value) => {
    if (value.theme) {
      value[appearance] = value.theme;
    }
    updateEditorSettings({
      ...editorSettings,
      ...value,
    });
  };

  useUpdateEffect(() => {
    form.setFieldsValue({
      theme: getEditorTheme(appearance),
    });
  }, [appearance]);

  const monacoEditorId = useMemo(() => uuid(), []);

  return (
    <div className={commonStyles.containerBlock}>
      <SettingSubsection title={i18n('setting.nav.editSetting')} describe={i18n('setting.nav.editSettingDescribe')} />

      <div className={styles.container}>
        <Form
          form={form}
          layout="vertical"
          name="login"
          initialValues={{ ...DEFAULT_EDITOR_SETTINGS, ...editorSettings }}
          onValuesChange={handleValuesChange}
          className={styles.formWrapper}
        >
          <Form.Item
            name="theme"
            label={i18n('monaco.theme')}
            style={{ width: '50%', minWidth: '160px' }}
            tooltip={{
              title: i18n('monaco.theme.tooltip'),
            }}
          >
            <InteractiveSelect
              onChange={(value) => {
                form.setFieldsValue({ theme: value });
              }}
              options={THEMES}
              popupMatchSelectWidth
            />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="fontFamily"
                label={i18n('monaco.fontFamily')}
                style={{ width: '50%', minWidth: '160px' }}
              >
                <InteractiveSelect
                  onChange={(value) => {
                    form.setFieldsValue({ fontFamily: value });
                  }}
                  options={fontFamilies}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                tooltip={{
                  title: i18n('monaco.customFontFamily.tooltip'),
                }}
                name="customFontFamily"
                label={i18n('setting.title.customFont')}
                style={{ width: '50%', minWidth: '160px' }}
              >
                <Input />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="fontSize" label={i18n('monaco.fontSize')}>
                <InputNumber min={12} max={24} step={1} addonAfter="px" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="lineHeight" label={i18n('monaco.lineHeight')}>
                <InputNumber min={1} max={3} step={0.1} precision={1} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="lineNumbers" label={i18n('monaco.lineNumbers')}>
            <Radio.Group>
              <Radio value="on">{i18n('monaco.lineNumbers.on')}</Radio>
              <Radio value="off">{i18n('monaco.lineNumbers.off')}</Radio>
            </Radio.Group>
          </Form.Item>

          <Form.Item name={['minimap', 'enabled']} label={i18n('monaco.minimap')}>
            <Radio.Group>
              <Radio value={true}>{i18n('monaco.minimap.on')}</Radio>
              <Radio value={false}>{i18n('monaco.minimap.off')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="wordWrap" label={i18n('monaco.wordWrap')}>
            <Radio.Group>
              <Radio value={'on'}>{i18n('monaco.wordWrap.on')}</Radio>
              <Radio value={'off'}>{i18n('monaco.wordWrap.off')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="folding" label={i18n('monaco.folding')}>
            <Radio.Group>
              <Radio value={true}>{i18n('monaco.minimap.on')}</Radio>
              <Radio value={false}>{i18n('monaco.minimap.off')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Col span={12}>
            <Form.Item name="renderLineHighlight" label={i18n('monaco.renderLineHighlight')}>
              <Select
                suffixIcon={<ChevronDown size={14} />}
                options={['line', 'none', 'gutter', 'all'].map((value) => ({
                  label: value.toUpperCase(),
                  value,
                }))}
              />
            </Form.Item>
          </Col>
          <Form.Item name="keywordCase" label={i18n('monaco.keywordCase')}>
            <Radio.Group>
              <Radio value={true}>{i18n('monaco.keywordCase.upper')}</Radio>
              <Radio value={false}>{i18n('monaco.keywordCase.lower')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Col span={12}>
            <Form.Item name="completion" label={i18n('monaco.completion.all')}>
              <Select
                mode="multiple"
                suffixIcon={<ChevronDown size={14} />}
                options={Object.values(databaseMap).map((value) => ({
                  label: value.name,
                  value: value.code,
                }))}
              />
            </Form.Item>
          </Col>
          <Form.Item name="errorContinue" label={i18n('monaco.errorContinue')}>
            <Radio.Group>
              <Radio value={true}>{i18n('monaco.errorContinue.true')}</Radio>
              <Radio value={false}>{i18n('monaco.errorContinue.false')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="tableDDLTriggerMode" label={i18n('monaco.tableDDLTriggerMode')}>
            <Radio.Group>
              {ddlTriggerOptions.map((option) => (
                <Radio key={option.value} value={option.value}>
                  {option.label}
                </Radio>
              ))}
            </Radio.Group>
          </Form.Item>
          <Form.Item name="completionAcceptKey" label={i18n('monaco.completionAcceptKey')}>
            <Radio.Group>
              <Radio value="enter">{i18n('monaco.completionAcceptKey.enter')}</Radio>
              <Radio value="tab">{i18n('monaco.completionAcceptKey.tab')}</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name={['stickyScroll', 'enabled']} label={i18n('monaco.stickyScroll')}>
            <Switch />
          </Form.Item>
        </Form>
        <div className={styles.editorWrapper}>
          <MonacoEditor
            id={monacoEditorId}
            options={{
              value: exampleSQL,
            }}
          />
        </div>
      </div>
    </div>
  );
}

export default EditorSettings;
