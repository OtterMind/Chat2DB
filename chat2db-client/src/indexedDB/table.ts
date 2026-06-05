
export interface IWorkspaceConsoleDDL {
  consoleId: string;
  ddl: string;
  userId?: string;
}

export const workspaceConsoleDDL = {
  name: 'workspaceConsoleDDL',
  primaryKey: {
    keyPath: 'consoleId',
    autoIncrement: true,
  },
  column: [
    {
      name: 'consoleId',
      isIndex: true,
      keyPath: 'consoleId',
      options: {
        unique: true,
      },
    },
    {
      name: 'userId',
      isIndex: true,
      keyPath: 'userId',
      options: {
        unique: false,
      },
    },
    {
      name: 'ddl',
      isIndex: true,
      keyPath: 'ddl',
      options: {
        unique: false,
      },
    },

  ],
}

export const aiChatStore = {
  name: 'aiChatStore',
  primaryKey: {
    keyPath: 'id',
    autoIncrement: false,
  },
  column: [
    {
      name: 'id',
      isIndex: true,
      keyPath: 'id',
      options: {
        unique: true,
      },
    },
    {
      name: 'state',
      isIndex: false,
      keyPath: 'state',
      options: {
        unique: false,
      },
    },
  ],
}

export const tableList = [
  {
    tableDetails: workspaceConsoleDDL,
  },
  {
    tableDetails: aiChatStore,
  },
]
