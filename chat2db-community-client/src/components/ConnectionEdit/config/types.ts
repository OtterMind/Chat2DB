import { InputType, AuthenticationType, SSHAuthenticationType } from './enum';
import { DatabaseTypeCode, OperationColumn } from '@/constants';
import { LangType } from '@/constants/settings';

export type ILocalizedConnectionText = {
  [LangType.EN_US]: string;
  [LangType.ZH_CN]: string;
  [LangType.JA_JP]: string;
  [LangType.ES_ES]?: string;
  [LangType.KO_KR]?: string;
};

export type ISelect = {
  value?: AuthenticationType | SSHAuthenticationType | string | boolean;
  label?: string;
  onChange?: (value: IConnectionConfig) => IConnectionConfig;
  rest?: {
    [key in string]: any
  }
  items?: IFormItem[];
};

export interface IFormItem {
  defaultValue: any;
  inputType: InputType;
  labelName?: ILocalizedConnectionText,
  labelKey?:
    | 'workspace.identityColor.label'
    | 'workspace.watermark.label'
    | 'workspace.watermark.contentLabel';
  name: string;
  required: boolean;
  selected?: any;
  selects?: ISelect[];
  labelTextAlign?: 'right';
  disabled?: boolean;
  placeholder?: ILocalizedConnectionText;
  placeholderKey?: 'workspace.watermark.contentPlaceholder';
  styles?: {
    width?: string; // Form width; percentages are recommended and the default is 100%.
    // Form-label width for English; pixels are recommended and the default is 70px.
    labelWidth?: ILocalizedConnectionText;
    labelAlign?: string; // Label alignment; defaults to left.
  },
  rows?: number;
  hidden?: boolean;
  fileTypes?: string[];
  fileContentMode?: 'text' | 'base64';
  maxLength?: number;
  helpKey?: 'workspace.identityColor.help' | 'workspace.watermark.help';
  visibleWhen?: {
    name: string;
    value?: any;
    values?: any[];
  };
  layoutGroup?: 'dataSourceIdentity';
}

// Data-source connection form JSON configuration.
export type IConnectionConfig = {
  type: DatabaseTypeCode;
  baseInfo: {
    items: IFormItem[];
    pattern: RegExp;
    template: string;
    excludes?: OperationColumn[];
  },
  driver?: {
    items: IFormItem[];
  }
  ssh: {
    items: IFormItem[];
  },
  extendInfo?: {
    key: string;
    value: any;
  }[],
  // TODO: Resolve form settings, then form.item settings, then defaults; global settings are not yet included.
  styles?: {
    width?: string; // Form width; percentages are recommended and the default is 100%.
    labelWidthEN?: string; // English label width; pixels are recommended and the default is 70px.
    labelWidthCN?: string; // Chinese label width; pixels are recommended and the default is 100px.
    labelAlign?: 'left' | 'right'; // Label alignment; defaults to left.
  }
};
