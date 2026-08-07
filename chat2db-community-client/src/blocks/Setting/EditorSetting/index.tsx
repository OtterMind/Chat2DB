import { useMemo } from 'react';
import i18n from '@/i18n';
import { useStyles } from './style';
import { Form, Input, InputNumber, Radio, Select, Switch } from 'antd';
import { useGlobalStore } from '@/store/global';

import { DEFAULT_EDITOR_SETTINGS, MonacoEditor, editorFontFamily, editorThemes } from '@/components/SQLEditor';
import exampleSQL from '@/components/SQLEditor/data/example.sql';
import InteractiveSelect from './InteractiveSelect';
import { osNow } from '@/utils';
import { v4 as uuid } from 'uuid';
import { databaseMap } from '@/constants';
import { useUpdateEffect } from 'ahooks';
import { Braces, ChevronDown, Monitor, Palette, Play, ShieldCheck } from 'lucide-react';
import SearchTargetLabel from '../SearchTargetLabel';

const THEMES = Object.entries(editorThemes).map(([key]) => ({ label: key, value: key }));
const FONT_FAMILIES = Object.entries(editorFontFamily).map(([key, value]) => ({ label: key, value }));
const FONT_FAMILIES_WINDOW = Object.entries(editorFontFamily)
  .filter(([key]) => key !== 'JetBrains Mono')
  .map(([key, value]) => ({ label: key, value }));
const { isMac, isWin } = osNow();

function EditorSettings() {
  const { styles, theme } = useStyles();
  const { appearance } = theme;
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
    <div className={styles.container}>
      <Form
        form={form}
        layout="vertical"
        name="login"
        initialValues={{ ...DEFAULT_EDITOR_SETTINGS, ...editorSettings }}
        onValuesChange={handleValuesChange}
        className={styles.formWrapper}
      >
        <section className={styles.settingSection} data-editor-setting-group="appearance">
          <h2 className={styles.sectionTitle}>
            <Palette aria-hidden="true" className={styles.sectionIcon} size={17} strokeWidth={1.8} />
            <span>{i18n('monaco.group.appearance')}</span>
          </h2>
          <div className={styles.fieldGrid}>
            <Form.Item
              className={styles.fullWidthField}
              name="theme"
              label={<SearchTargetLabel targetId="editor.theme">{i18n('monaco.theme')}</SearchTargetLabel>}
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
            <Form.Item
              name="fontFamily"
              label={<SearchTargetLabel targetId="editor.fontFamily">{i18n('monaco.fontFamily')}</SearchTargetLabel>}
            >
              <InteractiveSelect
                onChange={(value) => {
                  form.setFieldsValue({ fontFamily: value });
                }}
                options={fontFamilies}
              />
            </Form.Item>
            <Form.Item
              tooltip={{
                title: i18n('monaco.customFontFamily.tooltip'),
              }}
              name="customFontFamily"
              label={
                <SearchTargetLabel targetId="editor.customFontFamily">
                  {i18n('setting.title.customFont')}
                </SearchTargetLabel>
              }
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="fontSize"
              label={<SearchTargetLabel targetId="editor.fontSize">{i18n('monaco.fontSize')}</SearchTargetLabel>}
            >
              <InputNumber min={12} max={24} step={1} addonAfter="px" />
            </Form.Item>
            <Form.Item
              name="lineHeight"
              label={<SearchTargetLabel targetId="editor.lineHeight">{i18n('monaco.lineHeight')}</SearchTargetLabel>}
              tooltip={i18n('monaco.lineHeight.tooltip')}
            >
              <InputNumber min={1} max={3} step={0.1} precision={1} />
            </Form.Item>
          </div>
        </section>

        <section className={styles.settingSection} data-editor-setting-group="display">
          <h2 className={styles.sectionTitle}>
            <Monitor aria-hidden="true" className={styles.sectionIcon} size={17} strokeWidth={1.8} />
            <span>{i18n('monaco.group.display')}</span>
          </h2>
          <div className={styles.fieldGrid}>
            <Form.Item
              name="lineNumbers"
              label={<SearchTargetLabel targetId="editor.lineNumbers">{i18n('monaco.lineNumbers')}</SearchTargetLabel>}
            >
              <Radio.Group>
                <Radio value="on">{i18n('monaco.lineNumbers.on')}</Radio>
                <Radio value="off">{i18n('monaco.lineNumbers.off')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name={['minimap', 'enabled']}
              label={<SearchTargetLabel targetId="editor.minimap">{i18n('monaco.minimap')}</SearchTargetLabel>}
              tooltip={i18n('monaco.minimap.tooltip')}
            >
              <Radio.Group>
                <Radio value={true}>{i18n('monaco.minimap.on')}</Radio>
                <Radio value={false}>{i18n('monaco.minimap.off')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name="wordWrap"
              label={<SearchTargetLabel targetId="editor.wordWrap">{i18n('monaco.wordWrap')}</SearchTargetLabel>}
              tooltip={i18n('monaco.wordWrap.tooltip')}
            >
              <Radio.Group>
                <Radio value="on">{i18n('monaco.wordWrap.on')}</Radio>
                <Radio value="off">{i18n('monaco.wordWrap.off')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name="folding"
              label={<SearchTargetLabel targetId="editor.folding">{i18n('monaco.folding')}</SearchTargetLabel>}
              tooltip={i18n('monaco.folding.tooltip')}
            >
              <Radio.Group>
                <Radio value={true}>{i18n('monaco.minimap.on')}</Radio>
                <Radio value={false}>{i18n('monaco.minimap.off')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name="renderLineHighlight"
              label={
                <SearchTargetLabel targetId="editor.renderLineHighlight">
                  {i18n('monaco.renderLineHighlight')}
                </SearchTargetLabel>
              }
              tooltip={i18n('monaco.renderLineHighlight.tooltip')}
            >
              <Select
                suffixIcon={<ChevronDown size={14} />}
                options={[
                  { label: i18n('monaco.renderLineHighlight.line'), value: 'line' },
                  { label: i18n('monaco.renderLineHighlight.none'), value: 'none' },
                  { label: i18n('monaco.renderLineHighlight.gutter'), value: 'gutter' },
                  { label: i18n('monaco.renderLineHighlight.all'), value: 'all' },
                ]}
              />
            </Form.Item>
            <Form.Item
              name={['stickyScroll', 'enabled']}
              label={
                <SearchTargetLabel targetId="editor.stickyScroll">{i18n('monaco.stickyScroll')}</SearchTargetLabel>
              }
              tooltip={i18n('monaco.stickyScroll.tooltip')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </div>
        </section>

        <section className={styles.settingSection} data-editor-setting-group="completion">
          <h2 className={styles.sectionTitle}>
            <Braces aria-hidden="true" className={styles.sectionIcon} size={17} strokeWidth={1.8} />
            <span>{i18n('monaco.group.completion')}</span>
          </h2>
          <div className={styles.fieldGrid}>
            <Form.Item
              name="keywordCase"
              label={<SearchTargetLabel targetId="editor.keywordCase">{i18n('monaco.keywordCase')}</SearchTargetLabel>}
              tooltip={i18n('monaco.keywordCase.tooltip')}
            >
              <Radio.Group>
                <Radio value={true}>{i18n('monaco.keywordCase.upper')}</Radio>
                <Radio value={false}>{i18n('monaco.keywordCase.lower')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name="completionAcceptKey"
              label={
                <SearchTargetLabel targetId="editor.completionAcceptKey">
                  {i18n('monaco.completionAcceptKey')}
                </SearchTargetLabel>
              }
              tooltip={i18n('monaco.completionAcceptKey.tooltip')}
            >
              <Radio.Group>
                <Radio value="enter">{i18n('monaco.completionAcceptKey.enter')}</Radio>
                <Radio value="tab">{i18n('monaco.completionAcceptKey.tab')}</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              className={styles.fullWidthField}
              name="completion"
              label={
                <SearchTargetLabel targetId="editor.completion">{i18n('monaco.completion.all')}</SearchTargetLabel>
              }
              tooltip={i18n('monaco.completion.all.tooltip')}
            >
              <Select
                mode="multiple"
                suffixIcon={<ChevronDown size={14} />}
                options={Object.values(databaseMap).map((value) => ({
                  label: value.name,
                  value: value.code,
                }))}
              />
            </Form.Item>
            <Form.Item
              className={styles.fullWidthField}
              name="tableDDLTriggerMode"
              label={
                <SearchTargetLabel targetId="editor.tableDDLTriggerMode">
                  {i18n('monaco.tableDDLTriggerMode')}
                </SearchTargetLabel>
              }
              tooltip={i18n('monaco.tableDDLTriggerMode.tooltip', ddlClickModifierKey)}
            >
              <Radio.Group>
                {ddlTriggerOptions.map((option) => (
                  <Radio key={option.value} value={option.value}>
                    {option.label}
                  </Radio>
                ))}
              </Radio.Group>
            </Form.Item>
          </div>
        </section>

        <section className={styles.settingSection} data-editor-setting-group="behavior">
          <h2 className={styles.sectionTitle}>
            <ShieldCheck aria-hidden="true" className={styles.sectionIcon} size={17} strokeWidth={1.8} />
            <span>{i18n('monaco.group.behavior')}</span>
          </h2>
          <div className={styles.fieldGrid}>
            <Form.Item
              name="confirmBeforeClose"
              label={
                <SearchTargetLabel targetId="editor.confirmBeforeClose">
                  {i18n('monaco.confirmBeforeClose')}
                </SearchTargetLabel>
              }
              tooltip={i18n('monaco.confirmBeforeClose.tooltip')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </div>
        </section>

        <section className={styles.settingSection} data-editor-setting-group="execution">
          <h2 className={styles.sectionTitle}>
            <Play aria-hidden="true" className={styles.sectionIcon} size={17} strokeWidth={1.8} />
            <span>{i18n('monaco.group.execution')}</span>
          </h2>
          <div className={styles.fieldGrid}>
            <Form.Item
              name="errorContinue"
              label={
                <SearchTargetLabel targetId="editor.errorContinue">{i18n('monaco.errorContinue')}</SearchTargetLabel>
              }
              tooltip={i18n('monaco.errorContinue.tooltip')}
            >
              <Radio.Group>
                <Radio value={true}>{i18n('monaco.errorContinue.true')}</Radio>
                <Radio value={false}>{i18n('monaco.errorContinue.false')}</Radio>
              </Radio.Group>
            </Form.Item>
          </div>
        </section>
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
  );
}

export default EditorSettings;
