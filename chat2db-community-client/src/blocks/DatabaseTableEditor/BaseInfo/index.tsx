import { useContext, useEffect, useImperativeHandle, ForwardedRef, forwardRef, memo, useMemo } from 'react';
import classnames from 'classnames';
import { Form, Input } from 'antd';
import { Context } from '../index';
import { IBaseInfo } from '@/typings';
import { DatabaseCapability } from '@/constants';
import { isDatabaseCapabilitySupported } from '@/utils/databaseJudgments';
import i18n from '@/i18n';
import CustomSelect from '@/components/CustomSelect';
import { useStyles } from './style';
import { buildBaseInfoFormValues, filterCollationsByCharset, isCharsetCollationCompatible } from '../baseInfoModel';

export interface IBaseInfoRef {
  getBaseInfo: () => IBaseInfo;
}

interface IProps {
  className?: string;
}

const BaseInfo = forwardRef((props: IProps, ref: ForwardedRef<IBaseInfoRef>) => {
  const { className } = props;
  const { styles } = useStyles();
  const {
    tableDetails,
    databaseSupportField,
    databaseBaseInfo: { databaseType },
  } = useContext(Context);
  const [form] = Form.useForm();
  const selectedCharset = Form.useWatch('charset', form);
  const selectedCollation = Form.useWatch('collation', form);
  const filteredCollationOptions = useMemo(
    () => filterCollationsByCharset(databaseSupportField.collations, selectedCharset),
    [databaseSupportField.collations, selectedCharset],
  );

  useEffect(() => {
    form.setFieldsValue(buildBaseInfoFormValues(tableDetails));
  }, [tableDetails]);

  useEffect(() => {
    if (
      selectedCollation &&
      !isCharsetCollationCompatible(selectedCharset, selectedCollation, databaseSupportField.collations)
    ) {
      form.setFieldValue('collation', null);
    }
  }, [databaseSupportField.collations, form, selectedCharset, selectedCollation]);

  function getBaseInfo(): IBaseInfo {
    const values = form.getFieldsValue();
    // Backend Table uses `collate`; the form field is `collation`.
    return { ...values, collate: values.collation };
  }

  useImperativeHandle(ref, () => ({
    getBaseInfo,
  }));

  return (
    <div className={classnames(className, styles.baseInfo)}>
      <div className={styles.formBox}>
        <Form layout="vertical" form={form} initialValues={{ remember: true }} autoComplete="off">
          <Form.Item label={`${i18n('editTable.label.tableName')}:`} name="name">
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item label={`${i18n('editTable.label.comment')}:`} name="comment">
            <Input autoComplete="off" />
          </Form.Item>
          {isDatabaseCapabilitySupported(databaseType, DatabaseCapability.TABLE_EDITOR_BASE_INFO) && (
            <>
              <Form.Item label={`${i18n('editTable.label.characterSet')}:`} name="charset">
                <CustomSelect options={databaseSupportField.charsets} />
              </Form.Item>
              <Form.Item label={`${i18n('editTable.label.collation')}:`} name="collation">
                <CustomSelect options={filteredCollationOptions} />
              </Form.Item>
              <Form.Item label={`${i18n('editTable.label.engine')}:`} name="engine">
                <CustomSelect options={databaseSupportField.engineTypes} />
              </Form.Item>
              <Form.Item label={`${i18n('editTable.label.incrementValue')}:`} name="incrementValue">
                <Input autoComplete="off" />
              </Form.Item>
            </>
          )}
        </Form>
      </div>
    </div>
  );
});

export default memo(BaseInfo);
