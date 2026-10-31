import { Button, Tooltip } from 'antd';
import { CircleHelp } from 'lucide-react';

interface Props {
  label: string;
  title: string;
}

export default function HelpTooltip({ label, title }: Props) {
  return (
    <Tooltip title={title}>
      <Button
        type="text"
        size="small"
        aria-label={label}
        icon={<CircleHelp size={14} aria-hidden="true" />}
        style={{ width: 20, height: 20, padding: 0 }}
      />
    </Tooltip>
  );
}
