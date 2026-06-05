import { tableList } from './table';

// 创建数据库的方法
export const createDB = (dbName: string, version: number) => {
  return new Promise((resolve, reject) => {
    const request = window.indexedDB.open(dbName, version);
    request.onerror = (event: any) => {
      reject(event.target.error);
    };
    request.onsuccess = (event: any) => {
      resolve(event.target.result);
    };
    request.onupgradeneeded = (event: any) => {
      const db = event.target.result; // 数据库对象
      // 创建存储库
      tableList.forEach((item: any) => {
        const { tableDetails } = item;
        const objectStore = db.createObjectStore(tableDetails.name, tableDetails.primaryKey);
        tableDetails.column.forEach((i: any) => {
          if (i.isIndex) {
            objectStore.createIndex(i.name, i.keyPath, i.options);
          }
        });
      });
    };
  });
};

type TableType = 'workspaceConsoleDDL' | 'aiChatStore';

type DBType = 'chat2db';

// 添加数据
export const addData = (db: DBType, tableName: TableType, data: any) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.add(data);
    request.onsuccess = () => {
      resolve(true);
    };
    request.onerror = (error) => {
      reject(error);
    };
  });
};

// 通过索引删除数据
export const deleteDataByIndex = (db: DBType, tableName: TableType, indexName, indexValue) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.index(indexName).delete(indexValue);
    request.onsuccess = () => {
      resolve(true);
    };
    request.onerror = () => {
      reject(false);
    };
  });
};

// 通过主键删除数据
export const deleteData = (db: DBType, tableName: TableType, key: any) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.delete(key);
    request.onsuccess = () => {
      resolve(true);
    };
    request.onerror = () => {
      reject(false);
    };
  });
};

// 通过索引查询数据,支持传入多个索引
export const getDataByIndex = (db: DBType, tableName: TableType, indexName: string, indexValue: any) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.index(indexName).get(indexValue);
    request.onsuccess = () => {
      resolve(request.result);
    };
    request.onerror = () => {
      reject(false);
    };
  });
};

// 通过游标查询数据，支持传入多个条件
export const getDataByCursor = (db: DBType, tableName: TableType, condition: {[key in string]: any}
) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.openCursor();
    const result: any[] = [];
    request.onsuccess = (event: any) => {
      const cursor = event.target.result;
      if (cursor) {
        let flag = true;
        Object.keys(condition).forEach((key) => {
          if (cursor.value[key] !== condition[key]) {
            flag = false;
          }
        });
        if (flag) {
          result.push(cursor.value);
        }
        cursor.continue();
      } else {
        resolve(result);
      }
    };
    request.onerror = () => {
      reject(false);
    };
  });
 
};


// 修改数据
export const updateData = (db: DBType, tableName: TableType, data: any) => {
  return new Promise((resolve, reject) => {
    const transaction = window._indexedDB[db].transaction(tableName, 'readwrite');
    const objectStore = transaction.objectStore(tableName);
    const request = objectStore.put(data);
    request.onsuccess = () => {
      resolve(true);
    };
    request.onerror = () => {
      reject(false);
    };
  });
};

// 关闭数据库
export const closeDB = (db: DBType) => {
  return new Promise((resolve) => {
    window._indexedDB[db].close();
    resolve(true);
  });
};

export default {
  createDB,
  addData,
  deleteDataByIndex,
  deleteData,
  getDataByIndex,
  getDataByCursor,
  updateData,
  closeDB,
};
