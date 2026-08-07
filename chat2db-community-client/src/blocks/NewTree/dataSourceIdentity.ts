import { TreeNodeType } from '@/constants/tree';
import type { TreeNodeData } from '@/typings';
import {
  resolveDataSourceIdentityColor,
  withIdentityColorAlpha,
  type DataSourceIdentityColorSource,
} from '@/utils/dataSourceIdentity';
import type { CSSProperties } from 'react';

export const DATA_SOURCE_IDENTITY_NODE_CLASS = 'chat2db-data-source-identity-node';
export const DATA_SOURCE_IDENTITY_ROOT_CLASS = 'chat2db-data-source-identity-root';

type IdentityTreeNodeStyle = CSSProperties & {
  '--chat2db-data-source-identity-color': string;
  '--chat2db-data-source-identity-tint': string;
};

function appendClassName(className: string | undefined, nextClassName: string) {
  return Array.from(new Set([...(className || '').split(' ').filter(Boolean), nextClassName])).join(' ');
}

function stripIdentityClassNames(className?: string) {
  const nextClassName = (className || '')
    .split(' ')
    .filter(
      (name) =>
        name && name !== DATA_SOURCE_IDENTITY_NODE_CLASS && name !== DATA_SOURCE_IDENTITY_ROOT_CLASS,
    )
    .join(' ');
  return nextClassName || undefined;
}

function stripIdentityDecoration(node: TreeNodeData, children?: TreeNodeData[]): TreeNodeData {
  const className = stripIdentityClassNames(node.className);
  let style = node.style;
  if (
    style &&
    ('--chat2db-data-source-identity-color' in style || '--chat2db-data-source-identity-tint' in style)
  ) {
    const nextStyle = { ...style };
    delete nextStyle['--chat2db-data-source-identity-color'];
    delete nextStyle['--chat2db-data-source-identity-tint'];
    style = Object.keys(nextStyle).length ? nextStyle : undefined;
  }

  return {
    ...node,
    className,
    style,
    ...(children ? { children } : {}),
  };
}

export function decorateDataSourceIdentityTree(
  treeData: TreeNodeData[] | null,
  dataSourceList: TreeNodeData[] | null,
): TreeNodeData[] | null {
  if (!treeData) {
    return treeData;
  }

  const identitySources = new Map<number, DataSourceIdentityColorSource>();
  dataSourceList?.forEach((node) => {
    const dataSourceId = node.extraParams.dataSourceId;
    if (dataSourceId) {
      identitySources.set(dataSourceId, node.extraParams);
    }
  });

  const decorateNode = (node: TreeNodeData): TreeNodeData => {
    const dataSourceId = node.extraParams?.dataSourceId;
    const children = node.children?.map(decorateNode);
    const cleanNode = stripIdentityDecoration(node, children);
    if (!dataSourceId) {
      return cleanNode;
    }

    const color = resolveDataSourceIdentityColor(identitySources.get(dataSourceId) || node.extraParams);
    if (!color) {
      return cleanNode;
    }

    const isDataSourceRoot = node.treeNodeType === TreeNodeType.DATA_SOURCE;
    const style: IdentityTreeNodeStyle = {
      ...cleanNode.style,
      '--chat2db-data-source-identity-color': color,
      '--chat2db-data-source-identity-tint': withIdentityColorAlpha(color, isDataSourceRoot ? 0.1 : 0.06),
    };
    const identityClassName = appendClassName(cleanNode.className, DATA_SOURCE_IDENTITY_NODE_CLASS);
    const className = isDataSourceRoot
      ? appendClassName(identityClassName, DATA_SOURCE_IDENTITY_ROOT_CLASS)
      : identityClassName;

    return {
      ...cleanNode,
      className,
      style,
    };
  };

  return treeData.map(decorateNode);
}
