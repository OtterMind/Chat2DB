import i18n from '@/i18n';
import { Button, Form, InputNumber, Select } from 'antd';
import { Plus, Trash2 } from 'lucide-react';

import { agentArtifactTypes } from './agentOutputContract';
import { useStyles } from './style';

interface Props {
  form: ReturnType<typeof Form.useForm>[0];
}

export default function AgentOutputContractEditor({ form }: Props) {
  const { styles } = useStyles();
  const outputRequirements = Form.useWatch('outputRequirements', form) || [];
  return (
    <div className={styles.outputContractEditor}>
      <Form.List name="outputRequirements">
        {(fields, { add, remove }) => (
          <>
            <div className={styles.outputRequirementList}>
              {fields.map((field) => (
                <div className={styles.outputRequirementRow} key={field.key}>
                  <Form.Item
                    name={[field.name, 'type']}
                    rules={[
                      { required: true },
                      {
                        validator: (_, value) => outputRequirements.filter((item) => item?.type === value).length > 1
                          ? Promise.reject(new Error(i18n('task.agent.outputDuplicate')))
                          : Promise.resolve(),
                      },
                    ]}
                  >
                    <Select
                      aria-label={i18n('task.agent.outputType')}
                      options={agentArtifactTypes.map((type) => ({
                        value: type,
                        label: `${type} ${i18n(
                          `task.agent.artifact.${type.toLowerCase()}` as Parameters<typeof i18n>[0],
                        )}`,
                        disabled: outputRequirements.some(
                          (item, index) => index !== field.name && item?.type === type,
                        ),
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name={[field.name, 'min']} rules={[{ required: true }]}>
                    <InputNumber
                      min={1}
                      precision={0}
                      aria-label={i18n('task.agent.outputMinimum')}
                      addonBefore={i18n('task.agent.outputMinimum')}
                    />
                  </Form.Item>
                  <Button
                    type="text"
                    danger
                    icon={<Trash2 size={14} />}
                    aria-label={i18n('task.agent.outputRemove')}
                    disabled={fields.length === 1}
                    onClick={() => remove(field.name)}
                  />
                </div>
              ))}
            </div>
            <Button
              type="dashed"
              block
              icon={<Plus size={14} />}
              disabled={fields.length >= agentArtifactTypes.length}
              onClick={() => {
                const selected = form.getFieldValue('outputRequirements') || [];
                const nextType = agentArtifactTypes.find(
                  (type) => !selected.some((item) => item?.type === type),
                );
                if (nextType) add({ type: nextType, min: 1 });
              }}
            >
              {i18n('task.agent.outputAdd')}
            </Button>
          </>
        )}
      </Form.List>
      <Form.Item
        name="outputRequiredSections"
        label={i18n('task.agent.outputSections')}
        extra={i18n('task.agent.outputSectionsHint')}
      >
        <Select
          mode="tags"
          tokenSeparators={[',']}
          placeholder={i18n('task.agent.outputSectionsPlaceholder')}
        />
      </Form.Item>
    </div>
  );
}
