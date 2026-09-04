import { ErrorCode } from '@/constants/request';
import i18n from '@/i18n';
import type { IErrorLevel, PermissionError } from '@/service/base';
import { staticMessage } from '@chat2db/ui';

interface InterceptorResponseProps {
  errorCode: ErrorCode;
  errorMessage: string;
  requestParams: unknown;
  errorLevel: IErrorLevel;
  permissionError: PermissionError;
}

const interceptorsResponse = ({ errorCode }: InterceptorResponseProps) => {
  if (errorCode === ErrorCode.NetworkError) {
    staticMessage.error(i18n('common.text.notOnline'));
  }
};

export const resolveResponseErrorMessage = (errorCode: unknown, errorMessage: unknown) =>
  errorCode === 'transaction.implicitCommit.blocked' || errorMessage === 'transaction.implicitCommit.blocked'
    ? i18n('workspace.transaction.implicitCommitBlocked')
    : typeof errorMessage === 'string'
      ? errorMessage
      : '';

export default interceptorsResponse;
