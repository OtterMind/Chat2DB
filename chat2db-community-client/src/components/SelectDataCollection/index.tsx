import { memo, useEffect, useRef, useState } from 'react';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import i18n from '@/i18n';
import { useStyles } from './style';
import { IconfontSvg, ToolbarBtn } from '@chat2db/ui';
import { Modal, Select, Button, Space } from 'antd';
import ModalTitle from '@/components/Modal/ModalTitle';
import NodeAIDataCollection from '@/components/EmptyStates/NodeAIDataCollection';
import aiDataCollectionService from '@/service/aiDataCollection';
import { useWorkspaceStore } from '@/store/workspace';

export interface DefaultDataCollection {
  label: string;
  value: number;
}

interface IProps {
  className?: string;
  dataSourceId?: number;
  changeDataCollectionId?: (dataSourceCollectionId: number) => void;
  dataSourceCollectionId?: number;
  callSource: {
    type: 'dashboard' | 'console' | 'chat';
    id?: number;
  };
}

export default memo<IProps>((props) => {
  const { dataSourceId, dataSourceCollectionId, changeDataCollectionId, callSource } = props;
  const {
    styles,
    cx,
    theme: { appearance },
  } = useStyles();
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<any[]>([]);
  const [selectValue, setSelectValue] = useState<DefaultDataCollection>();

  const { defaultDataCollectionList, addDefaultDataCollectionList } = useWorkspaceStore((state) => ({
    addDefaultDataCollectionList: state.addDefaultDataCollectionList,
    defaultDataCollectionList: state.defaultDataCollectionList,
  }));

  const requestGenerationRef = useRef(0);

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    aiDataCollectionService.getAiDataCollectionList({ pageNo: 1, pageSize: 1000, dataSourceId }).then((res) => {
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      if (res) {
        const _options = res.data.map((item) => ({
          label: item.title,
          value: item.id,
        }));
        setOptions(_options);
      }
    });
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, [dataSourceId, open]);

  useEffect(() => {
    if (dataSourceCollectionId && options.length) {
      handleChange(dataSourceCollectionId);
    }
  }, [dataSourceCollectionId, options]);

  useEffect(() => {
    if (options.length && callSource && callSource?.id && defaultDataCollectionList && !dataSourceCollectionId) {
      handleChange(defaultDataCollectionList?.[callSource.type]?.[callSource?.id]);
    }
  }, [callSource, options, defaultDataCollectionList, dataSourceCollectionId]);

  const handleOk = () => {
    setOpen(false);
  };

  const handleChange = (value) => {
    if (!callSource?.id) {
      return;
    }
    const aiDataCollection = {
      label: options.find((item) => item.value === value)?.label,
      value,
    };
    setSelectValue(aiDataCollection);
    changeDataCollectionId?.(value);
    addDefaultDataCollectionList({
      ...callSource,
      value,
    } as any);
  };

  return (
    <>
      <Modal
        title={
          <ModalTitle
            title={i18n('workspace.title.selectAiDataCollection')}
            iconCode="icon-folder-close-ai"
            tips={{
              text: i18n('workspace.aiDataCollection.doc'),
              url: 'https://docs.chat2db-ai.com/docs/ai-chat/ai-data-collection',
            }}
          />
        }
        open={open}
        onCancel={() => {
          setOpen(false);
        }}
        footer={
          <>
            <Button
              onClick={() => {
                setOpen(false);
              }}
            >
              {i18n('common.button.cancel')}
            </Button>
            <Button type="primary" onClick={handleOk}>
              {i18n('common.button.affirm')}
            </Button>
          </>
        }
        maskClosable={false}
      >
        <Select
          placeholder={i18n('workspace.title.selectAiDataCollection')}
          className={styles.select}
          showSearch
          options={options}
          value={selectValue?.value}
          onChange={handleChange}
          notFoundContent={<NodeAIDataCollection />}
          optionRender={(option) => (
            <Space>
              <IconfontSvg existDark appearance={appearance} code="icon-colourful-folder-close-ai" />
              {option.data.label}
            </Space>
          )}
        />
      </Modal>
      <div className={styles.toolbarBtnBox}>
        <div className={styles.toolbarBtnBoxSecondLevel}>
          <ToolbarBtn
            onClick={() => {
              setOpen(true);
            }}
            className={cx({ [styles.fullToolbarBtn]: !!selectValue?.label }, styles.selectDataCollection)}
            text={selectValue?.label || i18n('workspace.aiDataCollection.select.placeholder')}
            prefixIcon={<IconfontSvg size="md" code="icon-folder-close-ai" />}
            suffixIcon={!selectValue?.label ? <IconfontSvg size="xs" code="icon-question-mark-circle" /> : undefined}
          />
        </div>
      </div>
    </>
  );
});
