import type { ModalFuncProps } from 'antd';

interface ModalConfirmApi {
  confirm: (config: ModalFuncProps) => unknown;
}

export const confirmBetaFeature = (
  modal: ModalConfirmApi,
  config: Pick<ModalFuncProps, 'title' | 'content' | 'okText' | 'cancelText'>,
) =>
  new Promise<boolean>((resolve) => {
    modal.confirm({
      ...config,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
