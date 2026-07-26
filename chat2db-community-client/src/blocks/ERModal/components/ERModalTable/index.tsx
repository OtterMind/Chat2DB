import { memo, useState } from 'react';
import { useStyles } from './style';
import { Handle, Position } from '@xyflow/react';
import { IconfontSvg } from '@chat2db/ui';
import { IERTableDetail, IERTableColumn } from '@/typings/er';

interface IConnectingNode {
  nodeId: string;
  handleId: string;
  handleType: 'source' | 'target';
}

interface IProps {
  className?: string;
  tableDetail: IERTableDetail;
  connectingNode: IConnectingNode | null;
  virtualForeignKeys: string[];
}

export default memo<IProps>((props) => {
  const { className, connectingNode, virtualForeignKeys } = props;
  const { styles, cx } = useStyles();
  const [tableDetail] = useState<IERTableDetail>(props.tableDetail);

  const foreignKeyList = tableDetail.foreignKeyList || [];

  const renderKeyIcon = (item: IERTableColumn) => {
    if (item.primaryKey) {
      return <IconfontSvg size={17} className={styles.primaryKeyIcon} code="icon-primary-Key" />;
    } else if (foreignKeyList.some((fk) => fk.pkColumnName === item.name)) {
      return <div className={styles.diamond} />;
    }
    return null;
  };

  const showForeignKey = (item: IERTableColumn) => {
    // if ('user_group' === tableDetail.name) {
    //   debugger;
    // }
    if (virtualForeignKeys.includes(`${tableDetail.name}_${item.name}`)) {
      return true;
    }

    if (foreignKeyList.some((fk) => fk.fkColumnName === item.name)) {
      return true;
    }

    if (connectingNode) {
      if (connectingNode.handleType === 'source' && connectingNode.nodeId !== tableDetail.name) {
        return true;
      }
    }

    return false;
  };

  return (
    <div className={cx(styles.container, className)}>
      <div className={styles.tableHeader}>
        {tableDetail.name}
        {tableDetail.comment && `(${tableDetail.comment})`}
      </div>
      <table className={styles.tableContent}>
        <tbody>
          {tableDetail.columnList.map((item) => (
            <tr key={item.name} className={styles.tableContentItem}>
              <td className={styles.keyIcon}>
                <div className={styles.keyIconContent}>
                  {renderKeyIcon(item)}
                  {!!item.primaryKey && (
                    <Handle
                      type={'source'}
                      position={Position.Right}
                      id={`${tableDetail.name}_${item.name}`}
                      className={cx(styles.fieldHandle, {
                        [styles.fieldHandleActive]: item.primaryKey,
                      })}
                      style={{
                        right: -1,
                        backgroundColor: '#ff0072',
                      }}
                    />
                  )}
                  <Handle
                    type={'target'}
                    position={Position.Left}
                    id={`${tableDetail.name}_${item.name}`}
                    className={cx(styles.fieldHandle, {
                      [styles.fieldHandleActive]: showForeignKey(item),
                    })}
                    style={{
                      left: -1,
                      backgroundColor: '#00ff72',
                    }}
                  />
                </div>
              </td>
              <td>{item.name}</td>
              <td>{item.columnType}</td>
              <td>{item.comment}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
});
