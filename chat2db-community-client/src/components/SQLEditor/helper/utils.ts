import { DatabaseTypeCode } from '@/constants';
import sqlServer from '@/service/sql';
import * as monaco from 'monaco-editor';
import { SqlStatement } from '@/typings/sqlParser';
import { osNow } from '@/utils';
/**
 * Format SQL.
 */

/**
 * Format SQL.
 * @param sql SQL statement.
 * @param dbType Database type.
 * @returns Formatted SQL.
 */
export function formatSql(sql: string, dbType?: DatabaseTypeCode): Promise<string> {
  // const supportedLanguages = new Set(Object.values(DatabaseTypeCode).map((value) => value.toLowerCase()));
  // const language = dbType?.toLowerCase();
  // const sqlLang = language && supportedLanguages.has(language) ? language : 'sql';
  // try {
  //   const formattedSql = format(sql ?? '', { language: sqlLang });
  //   if (formattedSql) {
  //     resolve(formattedSql);
  //     return;
  //   }
  // } catch (error) {
  //   console.error('Frontend SQL formatting error:', error);
  // }
  return new Promise((resolve) => {
    sqlServer
      .sqlFormat({ sql, dbType })
      .then(resolve)
      .catch((error) => {
        console.error('Server-side SQL formatting error:', error);
        resolve(sql);
      });
  });
}

/**
 * Find the parser object at the current cursor position.
 * @param curPosition
 * @param sqlStatementList
 * @returns
 */

export function findSqlStatement(curPosition: monaco.IPosition, sqlStatementList: SqlStatement[]) {
  return (sqlStatementList || []).find(
    (statement) =>
      (curPosition.lineNumber > statement.sqlStartRowNum ||
        (curPosition.lineNumber === statement.sqlStartRowNum && curPosition.column >= statement.sqlStartColNum)) &&
      (curPosition.lineNumber < statement.sqlEndRowNum ||
        (curPosition.lineNumber === statement.sqlEndRowNum && curPosition.column <= statement.sqlEndColNum)),
  );
}

export function findNearestSQL(curPosition: monaco.IPosition, sqlStatementList: SqlStatement[]) {
  const curLine = curPosition.lineNumber;
  const curCol = curPosition.column;

  let nearestStatement: SqlStatement | null = null;
  let minDistance = Infinity;

  if (!sqlStatementList) {
    return null;
  }
  for (const statement of sqlStatementList) {
    let distance;

    // Skip SQL statements outside the cursor's current line.
    if (curLine < statement.sqlStartRowNum || curLine > statement.sqlEndRowNum) {
      continue;
    }

      // Use -1 when the cursor is inside the SQL statement.
    if (findSqlStatement(curPosition, sqlStatementList)) {
      distance = -1;
    } else {
      // Otherwise calculate the minimum distance to the statement boundaries.
      const distanceToStart = Math.abs(curCol - statement.sqlStartColNum);
      const distanceToEnd = Math.abs(curCol - statement.sqlEndColNum);
      distance = Math.min(distanceToStart, distanceToEnd);
    }

      // Update the nearest SQL statement.
    if (distance < minDistance) {
      minDistance = distance;
      nearestStatement = statement;
    }

      // Return immediately when the statement containing the cursor is found.
    if (distance === -1) {
      return statement;
    }
  }

  return nearestStatement;
}

export const keyboardKey = (function () {
  if (osNow().isMac) {
    return {
      command: '⌘',
      Shift: '⇧',
    };
  }
  return {
    command: 'Ctrl',
    Shift: 'Shift',
  };
})();
