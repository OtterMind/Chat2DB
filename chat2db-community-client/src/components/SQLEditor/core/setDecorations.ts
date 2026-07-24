/**
 * setDeltaDecorations.ts
 * ---------------------
 * Configure decorations
 * ---------------------
 * 1. Add code block borders
 * 2. Add code block execution buttons
 */
import * as monaco from 'monaco-editor';
import { IRange } from '../type';
import { ISqlEditorHintVO } from '@/typings/sqlParser';

/**
 * Add code block borders
 */

const setCodeBorderDecorations = (_rangeList: IRange[]) => {
  // Remove blank lines at the beginning and end.
  const rangeList = filterEmptyLines(_rangeList);

  if (rangeList.length === 0) {
    return []; // Return an empty array when no ranges exist.
  }

  const decorations: monaco.editor.IModelDeltaDecoration[] = [
    // Top border for the first line.
    {
      range: new monaco.Range(rangeList[0].startLineNumber, 1, rangeList[0].startLineNumber, rangeList[0].endColumn),
      options: {
        isWholeLine: false,
        className: 'myCodeBlockOutlineTop',
      },
    },
    // Bottom border for the last line.
    {
      range: new monaco.Range(
        rangeList[rangeList.length - 1].startLineNumber,
        1,
        rangeList[rangeList.length - 1].startLineNumber,
        rangeList[rangeList.length - 1].endColumn,
      ),
      options: {
        isWholeLine: false,
        className: 'myCodeBlockOutlineBottom',
      },
    },

    // Left border for all lines.
    {
      range: new monaco.Range(rangeList[0].startLineNumber, 1, rangeList[rangeList.length - 1].startLineNumber, 1),
      options: {
        isWholeLine: false,
        className: 'myCodeBlockOutlineLeft',
      },
    },
    // Right border for all lines.
    {
      range: new monaco.Range(
        rangeList[0].startLineNumber,
        rangeList[0].endColumn,
        rangeList[rangeList.length - 1].startLineNumber,
        rangeList[rangeList.length - 1].endColumn,
      ),
      options: {
        isWholeLine: false,
        className: 'myCodeBlockOutlineRight',
      },
    },

    ...rangeList.slice(0, -1).map((range, i) => {
      // const isEmptyLine = (_range: IRange) => _range.startColumn + 1 === _range.endColumn;
      // if (!isEmptyLine(range) && isEmptyLine(rangeList[i + 1])) {
      //   // TODO: Handle a non-empty current line followed by an empty line.
      //   // Find the next non-empty line.
      //   let endLine = rangeList[i + 1].startLineNumber;
      //   for (let j = i + 1; j < rangeList.length; j++) {
      //     if (rangeList[j].startColumn !== rangeList[j].endColumn) {
      //       break;
      //     }
      //     endLine = rangeList[j].endLineNumber;
      //   }
      //   return {
      //     range: new monaco.Range(range.endLineNumber, range.endColumn, endLine, range.endColumn),
      //     options: {
      //       isWholeLine: false,
      //       className: 'myCodeBlockOutlineVertical',
      //     },
      //   };
      // }

      // Draw a bottom border on line i when range[i].endColumn is greater than range[i + 1].endColumn.
      if (range.endColumn > rangeList[i + 1].endColumn) {
        return {
          range: new monaco.Range(
            range.startLineNumber,
            rangeList[i + 1].endColumn,
            range.startLineNumber,
            range.endColumn,
          ),
          options: {
            isWholeLine: false,
            className: ' myCodeBlockOutlineBottom ',
          },
        };
      } else {
        // Draw a top border on line i + 1 when range[i].endColumn is less than range[i + 1].endColumn.
        return {
          range: new monaco.Range(
            rangeList[i + 1].startLineNumber,
            range.endColumn,
            rangeList[i + 1].startLineNumber,
            rangeList[i + 1].endColumn,
          ),
          options: {
            isWholeLine: false,
            className: ' myCodeBlockOutlineTop ',
          },
        };
      }
    }),
  ];

  return decorations;
};

/**
 * Add code block execution buttons
 */
const setExecuteButtonDecorations = (lineNumbers: number[]) => {
  const safeLineNumbers = Array.isArray(lineNumbers) ? lineNumbers : [];
  const decorations: monaco.editor.IModelDeltaDecoration[] = safeLineNumbers.map((lineNumber) => {
    return {
      range: new monaco.Range(lineNumber, 1, lineNumber, 1),
      options: {
        isWholeLine: false,
        glyphMarginClassName: 'execute-button-glyph',
      },
    };
  });

  return decorations;
};

const setInsertValueHighlightDecorations = (rangeList: IRange[]) => {
  const safeRangeList = Array.isArray(rangeList) ? rangeList : [];
  const decorations: monaco.editor.IModelDeltaDecoration[] = safeRangeList.map((range) => {
    return {
      range: new monaco.Range(range.startLineNumber, range.startColumn, range.endLineNumber, range.endColumn),
      options: {
        inlineClassName: 'insert-value-highlight',
      },
    };
  });

  return decorations;
};

const setSqlValueTypeHintDecorations = (
  editorHints: ISqlEditorHintVO[] | null | undefined,
): monaco.editor.IModelDeltaDecoration[] => {
  return (editorHints || [])
    .filter((hint) => hint.type === 'INSERT_VALUE')
    .flatMap((hint) => hint.items || [])
    .filter((item) => !!item.range && !!(item.label || item.fieldName))
    .map((item) => ({
      range: new monaco.Range(
        item.range!.startLineNumber,
        item.range!.startColumn,
        item.range!.endLineNumber,
        item.range!.endColumn,
      ),
      options: {
        stickiness: monaco.editor.TrackedRangeStickiness.GrowsOnlyWhenTypingAfter,
        after: {
          content: `· ${item.label || item.fieldName}`,
          inlineClassName: 'sql-value-type-hint',
          cursorStops: monaco.editor.InjectedTextCursorStops.Left,
        },
      },
    }));
};

const setTableIdentifierDecorations = (rangeList: IRange[]) => {
  const safeRangeList = Array.isArray(rangeList) ? rangeList : [];
  const decorations: monaco.editor.IModelDeltaDecoration[] = safeRangeList.map((range) => {
    return {
      range: new monaco.Range(range.startLineNumber, range.startColumn, range.endLineNumber, range.endColumn),
      options: {
        inlineClassName: 'table-identifier-underline',
      },
    };
  });

  return decorations;
};

export {
  setCodeBorderDecorations,
  setExecuteButtonDecorations,
  setInsertValueHighlightDecorations,
  setSqlValueTypeHintDecorations,
  setTableIdentifierDecorations,
};

function filterEmptyLines(rangeList) {
  if (rangeList.length === 0) return [];

  let start = 0;
  let end = rangeList.length - 1;

  const isEmptyLine = (range) => range.startColumn + 1 === range.endColumn;

  // Remove blank lines from the beginning.
  while (start < rangeList.length && isEmptyLine(rangeList[start])) {
    start++;
  }

  // Remove blank lines from the end.
  while (end >= start && isEmptyLine(rangeList[end])) {
    end--;
  }

  // // Handle blank lines in the middle.
  // for (let i = start; i <= end; i++) {
  //   if (isEmptyLine(rangeList[i])) {
  //     rangeList[i].endColumn = rangeList[i - 1].endColumn;
  //   }
  // }

  // Return the filtered array.
  return rangeList.slice(start, end + 1);
}
