import React, { memo, useState } from 'react';
import { useStyles } from './style';
import { Form, Input, Select, InputNumber, Button } from 'antd';
const { TextArea } = Input;
import { redisFieldTypeList, RedisFieldType } from '@/constants/redis';
import CreateList, { CreateListRef } from '../CreateList';
import CreateStream, { CreateStreamRef } from '../CreateStream';
import i18n from '@/i18n';
import redisServices from '@/service/nonRelationalDatabase/redis';
import { RedisDataItem } from '@/typings/redis';
import { useUpdateEffect } from 'ahooks';
import { cloneDeep } from 'lodash';

interface IProps {
  className?: string;
  redisDataItem: RedisDataItem;
  dataSourceId: number;
  databaseName: string;
  submitSuccess?: (redisDataItem) => void;
}

export interface FormValue {
  name: string;
  type: RedisFieldType;
  ttl: number;
}

export default memo<IProps>((props) => {
  const { className, redisDataItem, dataSourceId, databaseName, submitSuccess } = props;
  const originalRedisDataItem = cloneDeep(redisDataItem);
  const { styles, cx } = useStyles();
  const [form] = Form.useForm();
  const [formValue, setFormValue] = useState({
    ...redisDataItem,
  });
  const createListRef = React.useRef<CreateListRef>(null);
  const createStreamRef = React.useRef<CreateStreamRef>(null);
  const [buttonLoading, setButtonLoading] = useState(false);

  const onFinish = (values) => {
    createData(values);
  };

  useUpdateEffect(() => {
    setFormValue({
      ...redisDataItem,
    });
    form.setFieldsValue({
      ...redisDataItem,
    });
  }, [redisDataItem]);

  // const ttlOptions = [
  //   { label: 'NoneTTL', value: -1 },
  //   { label: 'Expiration time (seconds)', value: 1 },
  // ];

  const handleFormChange = (changedValues, allValues) => {
    setFormValue({
      ...formValue,
      ...allValues,
    });
  };

  const createData = (values) => {
    const valueListObj = createListRef.current?.getValueList() || {};
    let baseData: RedisDataItem = {
      name: values.name,
      ttl: values.ttl,
      type: values.type,
    };
    if (baseData.type === RedisFieldType.STRING) {
      baseData.value = values.value;
    } else if (baseData.type === RedisFieldType.STREAM) {
      baseData.streamValues = createStreamRef?.current?.getStreamList() || [];
    } else {
      baseData = {
        ...baseData,
        ...valueListObj,
      };
    }

    setButtonLoading(true);
    if (originalRedisDataItem.isDraftFE) {
      redisServices
        .createRedisData({
          dataSourceId,
          databaseName,
          ...baseData,
        })
        .then((_redisDataItem) => {
          submitSuccess && submitSuccess(_redisDataItem);
        })
        .finally(() => {
          setTimeout(() => {
            setButtonLoading(false);
          }, 200);
        });
    } else {
      redisServices
        .updateRedisData({
          dataSourceId,
          databaseName,
          oldRedisKey: originalRedisDataItem,
          newRedisKey: baseData,
        })
        .then((_redisDataItem) => {
          submitSuccess && submitSuccess(_redisDataItem);
        })
        .finally(() => {
          setTimeout(() => {
            setButtonLoading(false);
          }, 200);
        });
    }
  };

  return (
    <div className={cx(styles.editData, className)}>
      <Form
        form={form}
        name="editData"
        layout="vertical"
        initialValues={formValue}
        onFinish={onFinish}
        onValuesChange={handleFormChange}
        autoComplete="off"
        className={styles.form}
      >
        <div className={styles.firstLine}>
          <Form.Item label={i18n('redis.keyName')} name="name" className={styles.nameFormItem}>
            <Input />
          </Form.Item>

          <Form.Item label={i18n('redis.type')} name="type" className={styles.typeFormItem}>
            <Select options={redisFieldTypeList} />
          </Form.Item>

          <Form.Item label={i18n('redis.ttl')} name="ttl" className={styles.ttlFormItem}>
            <InputNumber style={{ width: '100%' }} min={-1} />
          </Form.Item>
        </div>
        <div className={styles.fullFormItemBox}>
          {/* string */}
          {formValue.type === RedisFieldType.STRING && (
            <Form.Item label={i18n('redis.value')} name="value" className={styles.textAreaFormItem}>
              <TextArea style={{ resize: 'none' }} />
            </Form.Item>
          )}

          {formValue.type !== RedisFieldType.STRING && formValue.type !== RedisFieldType.STREAM && (
            <div className={styles.createList}>
              <Form.Item label={i18n('redis.value')}>
                <CreateList
                  ref={createListRef}
                  type={formValue.type}
                  originalValueList={
                    formValue.values ||
                    formValue.listValues ||
                    formValue.hashValues ||
                    formValue.zsValues ||
                    []
                  }
                />
              </Form.Item>
            </div>
          )}

          {formValue.type === RedisFieldType.STREAM && (
            <div className={styles.createList}>
              <Form.Item label={i18n('redis.value')}>
                <CreateStream ref={createStreamRef} originalStreamList={formValue.streamValues || []} />
              </Form.Item>
            </div>
          )}
        </div>

        {/* <Form.Item label="TTL">
          <Select options={ttlOptions} />
        </Form.Item> */}

        <Form.Item>
          <Button type="primary" htmlType="submit" loading={buttonLoading}>
            {i18n('redis.button.submit')}
          </Button>
        </Form.Item>
      </Form>
    </div>
  );
});
