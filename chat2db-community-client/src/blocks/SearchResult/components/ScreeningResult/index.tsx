import React, { memo, useEffect, forwardRef, ForwardedRef, useImperativeHandle } from 'react';
import classnames from 'classnames';
import Iconfont from '@/components/Iconfont';
import SingleFileMonacoEditor from '@/components/SingleFileMonacoEditor';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { useStyles } from './style';
import { useUpdateEffect } from 'ahooks';
import { DatabaseTypeCode } from '@/constants/common';
import { composeResultQuery } from './queryComposer';

interface IProps {
  className?: string;
  promptWord: any[];
  onSearch?: () => void;
  originalSql: string;
  orderByText?: string;
  databaseType?: DatabaseTypeCode;
}

export interface IScreeningResultRef {
  getJointSQL: () => string;
}

const keywordHintList = [
  'AND',
  'OR',
  'NOT',
  'IS',
  'NULL',
  'IN',
  'IS NOT NULL',
  'IS NULL',
  'IS NOT',
  'NOT IN',
  'EXISTS',
  'BETWEEN',
  'LIKE',
  'ASC',
  'DESC',
];

const mongoKeywordHintList = [
  '$and',
  '$or',
  '$not',
  '$eq',
  '$ne',
  '$gt',
  '$gte',
  '$lt',
  '$lte',
  '$in',
  '$nin',
  '$exists',
];

const ScreeningResult = forwardRef((props: IProps, ref: ForwardedRef<IScreeningResultRef>) => {
  const { promptWord, onSearch, originalSql, orderByText, databaseType } = props;
  const { styles } = useStyles();
  const isMongoDB = databaseType === DatabaseTypeCode.MONGODB;
  const [isActive, setIsActive] = React.useState(false);
  const keywordHintRef = React.useRef<any>(null);
  const fieldHintRef = React.useRef<any>(null);
  const whereSingleFileMonacoEditorRef = React.useRef<any>(null);
  const orderBySingleFileMonacoEditorRef = React.useRef<any>(null);

  useEffect(() => {
    keywordHintRef.current && keywordHintRef.current.dispose();
    fieldHintRef.current && fieldHintRef.current.dispose();
    if (isActive) {
      registerPromptWord();
    }
    return () => {
      keywordHintRef.current && keywordHintRef.current.dispose();
      fieldHintRef.current && fieldHintRef.current.dispose();
    };
  }, [promptWord, isActive, databaseType]);

  useUpdateEffect(() => {
    if (orderByText) {
      orderBySingleFileMonacoEditorRef.current?.setValue(orderByText, 'cover');
      orderBySingleFileMonacoEditorRef.current?.onSearch();
    } else {
      orderBySingleFileMonacoEditorRef.current?.setValue('', 'cover');
      orderBySingleFileMonacoEditorRef.current?.onSearch();
    }
  }, [orderByText]);

  useImperativeHandle(ref, () => ({
    getJointSQL: () => {
      const whereValue = whereSingleFileMonacoEditorRef.current?.getAllContent().trim() || '';
      const orderByValue = orderBySingleFileMonacoEditorRef.current?.getAllContent().trim() || '';
      return composeResultQuery({ databaseType, filterValue: whereValue, orderByValue, originalSql });
    },
  }));

  // registration prompt word
  const registerPromptWord = () => {
    fieldHintRef.current = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: () => {
        return {
          suggestions: promptWord.slice(1).map((item) => {
            return {
              insertText: item.name,
              kind: monaco.languages.CompletionItemKind.Field,
              label: item.name,
            };
          }),
        };
      },
      triggerCharacters: [],
    });

    keywordHintRef.current = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: () => {
        return {
          suggestions: (isMongoDB ? mongoKeywordHintList : keywordHintList).map((item) => {
            return {
              insertText: item,
              kind: monaco.languages.CompletionItemKind.Keyword,
              label: item,
            };
          }),
        };
      },
      triggerCharacters: [],
    });
  };

  // const search = () => {
  //   const whereValue = whereSingleFileMonacoEditorRef.current?.getAllContent().trim() || '';
  //   const orderByValue = orderBySingleFileMonacoEditorRef.current?.getAllContent().trim() || '';
  //   let sql = whereValue ? originalSql + ' WHERE ' + whereValue : originalSql;
  //   sql = orderByValue ? sql + ' ORDER BY ' + orderByValue : sql;
  //   onSearch();
  // };

  const focusChange = (_isActive: boolean) => {
    setIsActive(_isActive);
  };

  return (
    <div className={styles.screeningResult}>
      <div className={styles.whereBox}>
        <div className={styles.titleBox}>
          <Iconfont box boxSize={20} classNameBox={styles.titleIcon} code="&#xe66a;" />
          <div
            className={classnames(styles.title, {
              [styles.activeTitle]: true,
            })}
          >
            {isMongoDB ? 'FIND' : 'WHERE'}
          </div>
        </div>
        <SingleFileMonacoEditor
          ref={whereSingleFileMonacoEditorRef}
          focusChange={focusChange}
          handelEnter={onSearch}
          className={styles.monacoEditor}
        />
      </div>
      <div className={styles.orderByBox}>
        <div className={styles.titleBox}>
          <Iconfont box boxSize={20} classNameBox={styles.titleIcon} code="&#xe69a;" />
          <div
            className={classnames(styles.title, {
              [styles.activeTitle]: true,
            })}
          >
            {isMongoDB ? 'SORT' : 'ORDER BY'}
          </div>
        </div>
        <SingleFileMonacoEditor
          ref={orderBySingleFileMonacoEditorRef}
          focusChange={focusChange}
          handelEnter={onSearch}
          className={styles.monacoEditor}
        />
      </div>
    </div>
  );
});

export default memo(ScreeningResult);
