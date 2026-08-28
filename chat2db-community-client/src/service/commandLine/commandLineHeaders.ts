interface CommandLineRequest {
  requestUrl: string;
  method: string;
  message: any;
  headers?: Record<string, string>;
}

export const buildCommandLineParams = (
  data: CommandLineRequest,
  id: string,
  language: string,
  timeZone: string,
) => ({
  actionType: 'execute',
  uuid: id,
  ...data,
  headers: {
    ...data.headers,
    'Accept-Language': language,
    'Time-Zone': timeZone,
  },
});
