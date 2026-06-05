import indexedDB from '@/indexedDB';

/** 初始化indexedDB */
const initIndexedDB = () => {
  indexedDB.createDB('chat2db', 2).then((db) => {
    window._indexedDB = {
      chat2db: db,
    };
  });
};

export default initIndexedDB;
