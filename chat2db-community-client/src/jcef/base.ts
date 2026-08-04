export const isJcefApiAvailable = (): boolean => {
  return typeof window !== 'undefined' && typeof window.javaQuery === 'function';
};

const createJcefApi = <T = any>(command: string, params?: any, uuid?: string): Promise<T> => {
  const request = {
    requestUrl: command,
    method: 'client-command',
    message: params === undefined ? undefined : JSON.stringify(params),
    uuid,
  };

  if (!isJcefApiAvailable()) {
    return Promise.reject(new Error('Java Query is not available'));
  }

  return new Promise<T>((resolve, reject) => {
    window.javaQuery({
      request: JSON.stringify(request),
      onSuccess: function (_data) {
        try {
          const parsedData = JSON.parse(_data);
          const data = (parsedData?.data !== undefined ? parsedData.data : parsedData) as T;
          resolve(data);
        }
        catch {
          resolve(_data as T);
        }
      },
      onFailure: function (error_code, error_message) {
        reject(error_message);
      },
    });
  });
};

export default createJcefApi;
