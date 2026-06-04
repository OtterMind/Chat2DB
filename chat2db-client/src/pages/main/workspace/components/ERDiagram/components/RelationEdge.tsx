/**
 * ER图自定义边组件
 * 用于显示表之间的外键关系，区分真实外键（实线）和虚拟外键（虚线）
 */
import React, { memo } from 'react';
import {
  BaseEdge,
  EdgeLabelRenderer,
  EdgeProps,
  getSmoothStepPath,
} from '@xyflow/react';
import styles from './RelationEdge.less';

const RelationEdge = memo(({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  style = {},
  markerEnd,
}: EdgeProps) => {
  // 判断是否为虚拟外键（根据命名规范推断的外键）
  const isVirtual = (data as any)?.virtual;
  // 边标签，显示外键字段对应关系
  const label = (data as any)?.label;

  // 计算平滑路径，使用圆角连接
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 8,
  });

  return (
    <>
      {/* 边主体：真实外键用灰色实线，虚拟外键用橙色虚线 */}
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          ...style,
          stroke: isVirtual ? 'var(--color-warning)' : 'var(--color-text-tertiary)',
          strokeWidth: isVirtual ? 1.5 : 2,
          strokeDasharray: isVirtual ? '5 3' : undefined,
        }}
        markerEnd={markerEnd}
      />
      {/* 边标签：显示字段对应关系 */}
      {label && (
        <EdgeLabelRenderer>
          <div
            className={`${styles.edgeLabel} ${isVirtual ? styles.virtualLabel : ''}`}
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
});

export default RelationEdge;
