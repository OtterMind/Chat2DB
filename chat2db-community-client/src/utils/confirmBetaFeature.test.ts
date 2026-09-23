import assert from 'node:assert/strict';
import type { ModalFuncProps } from 'antd';
import { confirmBetaFeature } from './confirmBetaFeature';

let captured: ModalFuncProps | undefined;
const modal = {
  confirm: (config: ModalFuncProps) => {
    captured = config;
  },
};

const run = async () => {
  const accepted = confirmBetaFeature(modal, { title: 'Enable' });
  await captured?.onOk?.();
  assert.equal(await accepted, true);

  const cancelled = confirmBetaFeature(modal, { title: 'Enable' });
  captured?.onCancel?.();
  assert.equal(await cancelled, false);
};

run();
