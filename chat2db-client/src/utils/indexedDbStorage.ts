import { StateStorage } from 'zustand/middleware';

export const createIndexedDbStorage = (tableName: string, rowKey: string): StateStorage => {
  return {
    getItem: async (name: string): Promise<string | null> => {
      try {
        const db = window._indexedDB?.chat2db;
        if (!db) {
          return null;
        }
        return await new Promise<string | null>((resolve, reject) => {
          const transaction = db.transaction(tableName, 'readonly');
          const objectStore = transaction.objectStore(tableName);
          const request = objectStore.get(rowKey);
          request.onsuccess = () => {
            const result = request.result;
            if (!result) {
              resolve(null);
              return;
            }
            const value = result[name];
            if (typeof value === 'string') {
              resolve(value);
            } else {
              resolve(null);
            }
          };
          request.onerror = () => reject(request.error);
        });
      } catch (e) {
        console.error('[IndexedDbStorage] getItem error:', e);
        return null;
      }
    },

    setItem: async (name: string, value: string): Promise<void> => {
      try {
        const db = window._indexedDB?.chat2db;
        if (!db) {
          return;
        }
        await new Promise<void>((resolve, reject) => {
          const transaction = db.transaction(tableName, 'readwrite');
          const objectStore = transaction.objectStore(tableName);
          const request = objectStore.get(rowKey);
          request.onsuccess = () => {
            const existing = request.result || { id: rowKey };
            existing[name] = value;
            const putReq = objectStore.put(existing);
            putReq.onsuccess = () => resolve();
            putReq.onerror = () => reject(putReq.error);
          };
          request.onerror = () => reject(request.error);
        });
      } catch (e) {
        console.error('[IndexedDbStorage] setItem error:', e);
      }
    },

    removeItem: async (name: string): Promise<void> => {
      try {
        const db = window._indexedDB?.chat2db;
        if (!db) {
          return;
        }
        await new Promise<void>((resolve, reject) => {
          const transaction = db.transaction(tableName, 'readwrite');
          const objectStore = transaction.objectStore(tableName);
          const request = objectStore.get(rowKey);
          request.onsuccess = () => {
            const existing = request.result;
            if (!existing) {
              resolve();
              return;
            }
            delete existing[name];
            const putReq = objectStore.put(existing);
            putReq.onsuccess = () => resolve();
            putReq.onerror = () => reject(putReq.error);
          };
          request.onerror = () => reject(request.error);
        });
      } catch (e) {
        console.error('[IndexedDbStorage] removeItem error:', e);
      }
    },
  };
};
