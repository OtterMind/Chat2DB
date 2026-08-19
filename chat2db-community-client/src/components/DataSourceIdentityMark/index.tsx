import { theme } from 'antd';
import { memo } from 'react';
import { useDataSourceIdentityColor } from './useDataSourceIdentityColor';

export { useDataSourceIdentityColor } from './useDataSourceIdentityColor';

export interface DataSourceIdentityMarkProps {
  dataSourceId?: number;
  className?: string;
  title?: string;
  size?: number;
}

const DataSourceIdentityMark = memo(({ dataSourceId, className, title, size = 8 }: DataSourceIdentityMarkProps) => {
  const { token } = theme.useToken();
  const color = useDataSourceIdentityColor(dataSourceId);

  if (!dataSourceId || !color) {
    return null;
  }

  return (
    <span
      aria-hidden="true"
      className={className}
      title={title}
      data-testid="data-source-identity-mark"
      style={{
        display: 'inline-block',
        flex: `0 0 ${size}px`,
        width: size,
        height: size,
        boxSizing: 'border-box',
        border: `1px solid ${token.colorBorder}`,
        borderRadius: 2,
        backgroundColor: color,
      }}
    />
  );
});

DataSourceIdentityMark.displayName = 'DataSourceIdentityMark';

export default DataSourceIdentityMark;
