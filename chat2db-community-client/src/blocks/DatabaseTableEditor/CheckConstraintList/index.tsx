import { forwardRef, useContext, useEffect, useImperativeHandle, useState } from 'react';
import { Button, Input, Table } from 'antd';
import { Context } from '..';
import { EditColumnOperationType } from '@/constants';
import { ICheckConstraintItem } from '@/typings';

export interface ICheckConstraintListRef {
  getCheckConstraintListInfo: () => ICheckConstraintItem[];
}

const CheckConstraintList = forwardRef<ICheckConstraintListRef>((_, ref) => {
  const { tableDetails } = useContext(Context);
  const [constraints, setConstraints] = useState<ICheckConstraintItem[]>([]);

  useEffect(() => setConstraints(tableDetails.checkConstraintList || []), [tableDetails]);
  useImperativeHandle(ref, () => ({ getCheckConstraintListInfo: () => constraints }));

  const update = (index: number, field: keyof ICheckConstraintItem, value: string) => {
    setConstraints((current) => current.map((item, itemIndex) => itemIndex === index
      ? { ...item, [field]: value, editStatus: item.editStatus || EditColumnOperationType.Modify }
      : item));
  };

  return <>
    <Button onClick={() => setConstraints((current) => [...current, {
      name: '', expression: '', editStatus: EditColumnOperationType.Add,
    }])}
    >Add constraint</Button>
    <Table rowKey={(item) => item.name} pagination={false} dataSource={constraints} columns={[
      { title: 'Name', dataIndex: 'name', render: (value, _, index) => <Input value={value} onChange={(event) => update(index, 'name', event.target.value)} /> },
      { title: 'Expression', dataIndex: 'expression', render: (value, _, index) => <Input value={value} onChange={(event) => update(index, 'expression', event.target.value)} /> },
      { title: '', render: (_, item, index) => <Button danger onClick={() => setConstraints((current) => item.editStatus === EditColumnOperationType.Add
        ? current.filter((_, itemIndex) => itemIndex !== index)
        : current.map((entry, itemIndex) => itemIndex === index ? { ...entry, editStatus: EditColumnOperationType.Delete } : entry))}
                                               >Delete</Button> },
    ]}
    />
  </>;
});

export default CheckConstraintList;
