import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { LangType } from '@/constants/settings';
import { ITreeNode, TreeNodeData } from '@/typings';
import _copyToClipboard from 'copy-to-clipboard';
import lodash from 'lodash';
import queryString from 'query-string';
import React from 'react';
import { v4 as uuid } from 'uuid';
import { isDesktop } from './env';
import { clearInternalClipboard } from './internalClipboard';
import { findNode } from './treeNodeLookup';

export { findNode, getParentNode } from './treeNodeLookup';

export function deepClone(target: any) {
  const map = new WeakMap();

  function isObject(_target: any) {
    return (typeof _target === 'object' && _target) || typeof _target === 'function';
  }

  function clone(data: any) {
    if (!isObject(data)) {
      return data;
    }
    if ([Date, RegExp].includes(data.constructor)) {
      return new data.constructor(data);
    }
    if (typeof data === 'function') {
      return new Function('return ' + data.toString())();
    }
    const exist = map.get(data);
    if (exist) {
      return exist;
    }
    if (data instanceof Map) {
      const result = new Map();
      map.set(data, result);
      data.forEach((val, key) => {
        if (isObject(val)) {
          result.set(key, clone(val));
        } else {
          result.set(key, val);
        }
      });
      return result;
    }
    if (data instanceof Set) {
      const result = new Set();
      map.set(data, result);
      data.forEach((val) => {
        if (isObject(val)) {
          result.add(clone(val));
        } else {
          result.add(val);
        }
      });
      return result;
    }
    const keys = Reflect.ownKeys(data);
    const allDesc = Object.getOwnPropertyDescriptors(data);
    const result = Object.create(Object.getPrototypeOf(data), allDesc);
    map.set(data, result);
    keys.forEach((key) => {
      const val = data[key];
      if (isObject(val)) {
        result[key] = clone(val);
      } else {
        result[key] = val;
      }
    });
    return result;
  }

  return clone(target);
}

// Fuzzy match tree and highlight
export function approximateTreeNode(treeData: ITreeNode[], target: string = '', isDelete = true) {
  if (target) {
    const newTree: ITreeNode[] = lodash.cloneDeep(treeData || []);
    newTree.map((item, index) => {
      // No recursion for now, just search datasource
      // if(item.children?.length){
      //   item.children = approximateTreeNode(item.children, target,false);
      // }
      if (item.name?.toUpperCase()?.indexOf(target?.toUpperCase()) == -1 && isDelete) {
        delete newTree[index];
      } else {
        item.name = item.name?.replace(target, `<span style='color:red;'>${target}</span>`);
      }
    });
    return newTree.filter((i) => i);
  } else {
    return treeData;
  }
}

// Fuzzy match tree and highlight
export function approximateList<T, K extends keyof T>(
  data: T[],
  target: string,
  // @ts-expect-error The default key is valid for tree-like data used by this helper.
  keyName: K = 'name',
  isDelete = true,
) {
  if (target) {
    const newData: T[] = lodash.cloneDeep(data || []);
    newData.map((item, index) => {
      // No recursion for now, just search datasource
      // if(item.children?.length){
      //   item.children = approximateTreeNode(item.children, target,false);
      // }
      // @ts-expect-error Generic values are validated by the caller's key selection.
      if (item[keyName]?.toUpperCase()?.indexOf(target?.toUpperCase()) == -1 && isDelete) {
        delete newData[index];
      } else {
        // @ts-expect-error String replacement is only used with string-valued keys.
        item[keyName] = item[keyName]?.replace(target, `<span style='color:red;'>${target}</span>`);
      }
    });
    return newData.filter((i) => i);
  } else {
    return data;
  }
}

// Get the value of var variable
export const callVar = (css: string) => {
  return getComputedStyle(document.documentElement)
    .getPropertyValue(css)
    .trim();
};

// Give me an obj[], and the key and value of obj, and return the index to you.
export function findObjListValue<T, K extends keyof T>(list: T[], key: K, value: any) {
  let flag = -1;
  list.forEach((t: T, index) => {
    Object.keys(t).forEach((j: K) => {
      if (j === key && t[j] === value) {
        flag = index;
      }
    });
  });
  return flag;
}

// Clean up incompatible LocalStorage for the current edition only. This avoids
// clearing Pro, Local, and Community state when they share the same origin.
export function clearOlderLocalStorage() {
  const versionKey = runtimeEditionConfig.localStorageVersionKey;
  if (localStorage.getItem(versionKey) !== 'v6') {
    [
      runtimeEditionConfig.globalStoreName,
      runtimeEditionConfig.userStoreName,
      runtimeEditionConfig.orgStoreName,
      runtimeEditionConfig.workspaceStoreName,
      runtimeEditionConfig.aiStoreName,
      runtimeEditionConfig.treeStoreName,
    ].forEach((key) => localStorage.removeItem(key));
    localStorage.setItem(versionKey, 'v6');
  }
}

// Log out and clean up localStorage of some record locations
export function logoutClearSomeLocalStorage() {
  localStorage.removeItem(runtimeEditionConfig.currentWorkspaceDatabaseStorageKey);
  localStorage.removeItem(runtimeEditionConfig.currentConnectionStorageKey);
  localStorage.removeItem(runtimeEditionConfig.activeConsoleIdStorageKey);
  localStorage.removeItem(runtimeEditionConfig.currentPageStorageKey);
}

// Determine whether an updated version is needed
export function isVersionHigher(version: string, currentVersion: string): boolean {
  // Split the version number by .
  const versionParts = version.split('.');
  const currentVersionParts = currentVersion.split('.');

  // Compare the size of each bit in order from left to right
  for (let i = 0; i < versionParts.length; i++) {
    const part = parseInt(versionParts[i]);
    const currentPart = parseInt(currentVersionParts[i] || '0');

    if (part > currentPart) {
      return true;
    } else if (part < currentPart) {
      return false;
    }
  }

  // If the two version numbers are exactly equal, return false
  return false;
}

// Get some basic information about the application
export function getApplicationMessage() {
  const env = __RUNTIME_ENV__;
  const versions = __APP_VERSION__;
  const buildTime = __BUILD_TIME__;
  const userAgent = navigator.userAgent;
  return {
    env,
    versions,
    buildTime,
    userAgent,
  };
}

// os is mac or windows
export function osNow(): {
  isMac: boolean;
  isWin: boolean;
} {
  const agent = navigator.userAgent.toLowerCase();
  const isMac = /macintosh|mac os x/i.test(navigator.userAgent);
  const isWin =
    agent.indexOf('win32') >= 0 ||
    agent.indexOf('wow32') >= 0 ||
    agent.indexOf('win64') >= 0 ||
    agent.indexOf('wow64') >= 0;
  return {
    isMac,
    isWin,
  };
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

// Get cookies
export function getCookie(name: string) {
  const arr = document.cookie.match(new RegExp('(^| )' + name + '=([^;]*)(;|$)'));
  if (arr != null) {
    return decodeURIComponent(arr[2]);
  }
  return null;
}

// Determine the size of two versions
export function compareVersion(version1: string, version2: string) {
  const v1 = version1.split('.');
  const v2 = version2.split('.');
  const len = Math.max(v1.length, v2.length);

  while (v1.length < len) {
    v1.push('0');
  }
  while (v2.length < len) {
    v2.push('0');
  }

  for (let i = 0; i < len; i++) {
    const num1 = parseInt(v1[i]);
    const num2 = parseInt(v2[i]);

    if (num1 > num2) {
      return 1;
    } else if (num1 < num2) {
      return -1;
    }
  }

  return 0;
}

// Convert the contents of the clipboard into a two-dimensional array
export function clipboardToArray(text: string): Array<Array<string | null>> {
  if (!text) {
    return [[]];
  }
  try {
    const rows = text.split('\n');
    const array2D = rows.map((row) => row.split('\t'));
    return array2D;
  } catch {
    console.log('copy error');
    return [[]];
  }
}

export function copyToClipboard(
  data: string | number | Array<string | number | null> | Array<Array<string | null>>,
  direction: 'horizontal' | 'vertical' = 'horizontal',
) {
  clearInternalClipboard();
  try {
    let text = '';
    if (typeof data === 'string' || typeof data === 'number') {
      text = data.toString();
    } else if (data instanceof Array) {
      if (data[0] instanceof Array) {
        // two-dimensional array
        text = (data as Array<Array<string | null>>)
          .map((row) => row.map((item) => (item === null ? '' : item)).join('\t'))
          .join('\n');
      } else {
        // one-dimensional array
        const separator = direction === 'horizontal' ? '\t' : '\n';
        text = (data as Array<string | number | null>)
          .map((item) => (item === null ? '' : item.toString()))
          .join(separator);
        // Ensure last item is accounted for in the text
        if (direction === 'vertical' && text.length > 0) {
          text += '\n';
        }
      }
    }

    // For empty strings, we force a space and then delete it to ensure the clipboard is cleared
    if (text === '') {
      _copyToClipboard(' ', { format: 'text/plain' });
      return _copyToClipboard('', { format: 'text/plain' });
    }
    return _copyToClipboard(text, { format: 'text/plain' });

    // staticMessage.success('Copied to clipboard');
  } catch {
    console.log('copy error');
    return false;
  }
}

export function refreshPage() {
  // When desktop reloads with this parameter, it does not need to restart the Java service.
  if (isDesktop) {
    const url = new URL(window.location.href);
    const params = new URLSearchParams(url.search);
    params.set('reload', 'true');
    url.search = params.toString();
    // If the URL is the same, refresh directly
    if (url.toString() === window.location.href) {
      window.location.reload();
      return;
    }
    window.location.href = url.toString();
  } else {
    window.location.reload();
  }
}

// Get the browser's language
export function getUserComputerLanguage() {
  let language: any = '';
  if (isDesktop) {
    language = navigator.app_language;
  } else {
    language = navigator.language;
  }
  const { language: queryStringLanguage } = queryString.parse(location.search);
  if (typeof queryStringLanguage === 'string') {
    language = queryStringLanguage;
  }
  const finalLanguage = getLanguageType(language);
  return finalLanguage;
}

// Receive a string and convert it into language
export function getLanguageType(language?: string) {
  if (!language) {
    return LangType.EN_US;
  }
  const normalizedLanguage = language.toLowerCase();
  if (normalizedLanguage.startsWith('zh-cn')) {
    return LangType.ZH_CN;
  } else if (normalizedLanguage.startsWith('ja')) {
    return LangType.JA_JP;
  } else if (normalizedLanguage.startsWith('es')) {
    return LangType.ES_ES;
  } else if (normalizedLanguage.startsWith('ko')) {
    return LangType.KO_KR;
  } else {
    return LangType.EN_US;
  }
}

export function removeSubkeys(expandedKeys: React.Key[], tree: TreeNodeData[], targetKey: React.Key): React.Key[] {
  function getSubkeys(node: TreeNodeData): React.Key[] {
    let keys = [node.key]; // Includes the key of the current node
    if (node.children && node.children.length > 0) {
      node.children.forEach((child) => {
        keys = keys.concat(getSubkeys(child)); // Recursively obtain the key of the child node
      });
    }
    return keys;
  }

  // Get target node
  const targetNode = findNode(targetKey, tree);

  if (!targetNode) {
    return expandedKeys; // If the target node does not exist, return the original expandedKeys directly.
  }

  // Get the key of the target node and all its child nodes
  const keysToRemove = new Set(getSubkeys(targetNode));

  // Delete the keys of the target node and its child nodes from expandedKeys
  return expandedKeys.filter((key) => !keysToRemove.has(key));
}

// Find a node in the tree, operate on this node, and then return the new tree
export const findNodeAndOperate = (
  key: React.Key,
  tree: TreeNodeData[],
  operation: (node: TreeNodeData) => TreeNodeData,
): TreeNodeData[] => {
  return tree.map((node) => {
    if (node.key === key) {
      return operation(node);
    } else if (node.children) {
      return { ...node, children: findNodeAndOperate(key, node.children, operation) };
    }
    return node;
  });
};

// export function searchTreeNodes(treeNodes: TreeNodeData[], searchValue: string): TreeNodeData[] {
//   const result: TreeNodeData[] = [];

//   function traverse(node: TreeNodeData, _searchValue: string): TreeNodeData | null {
//     if (isMatched(_searchValue, [node.originalTitle, node.describe || ''])) {
//       return { ...node };
//     } else if (node.children) {
//       const matchingChildren = node.children.map((child) => traverse(child, _searchValue)).filter(Boolean);
//       if (matchingChildren.length > 0) {
//         return { ...node, children: matchingChildren };
//       }
//     }
//     return null;
//   }

//   (treeNodes || []).forEach((node) => {
//     const matchingNode = traverse(node, searchValue);
//     if (matchingNode) {
//       result.push(matchingNode);
//     }
//   });

//   return result;
// }

export { isMatched, isMatchedAndReplace, searchTreeNodes } from './searchTreeNodes';

// Compare each pair passed to React.memo's equality callback.
// Return true only when every pair contains equal values.
export function isEqualMemo(...args: any[]) {
  for (let i = 0; i < args.length; i++) {
    if (!lodash.isEqual(args[i][0], args[i][1])) {
      return false;
    }
  }
  return true;
}

// Prefix an ID to mark it as temporary. Generate a UUID when no ID is supplied.
export function getTemporaryId(id?: string | number) {
  if (id === undefined) {
    return `chat2db_temporary_${uuid()}`;
  }
  return `chat2db_temporary_${id}`;
}

export const randomLargeLong = () => {
  // Java's Long.MAX_VALUE is 2^63 - 1
  // We'll generate a number between 2^50 and 2^63 - 1
  const min = Math.pow(2, 50);
  const max = Number.MAX_SAFE_INTEGER; // This is 2^53 - 1, the largest safe integer in JavaScript
  // Generate two random numbers and combine them
  const result = Math.floor(Math.random() * (Math.floor(max / min) - min + 1)) + min;

  // Ensure the result is a positive integer
  return Math.abs(result) >>> 0;
};

// Pass me a number or string and I will determine whether the ID is temporary.
export function isTemporaryId(id: string | number) {
  return typeof id === 'string' && id.startsWith('chat2db_temporary');
}

export function truncateString(str: string | null | undefined, length: number = 100): string | null {
  if (str === null || str === undefined) {
    return null;
  }
  if (str.length <= length) {
    return str;
  }
  return str.substring(0, length) + '...';
}

// Parse JSON values and return all other values unchanged.
export function parseJson(value: any) {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}
