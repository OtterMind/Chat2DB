import { ACTIVE_TRANSACTION_PROCESS_PRIVILEGE_ERROR_CODE } from '@/constants/activeTransaction';
import type { IActiveTransactionItem } from '@/service/sql';
import dayjs from 'dayjs';
import {
  beginLatestRequest,
  invalidateLatestRequest,
  isLatestRequest,
  RequestGenerationRef,
} from '@/utils/latestRequest';

export function getActiveTransactionRowKey(record: IActiveTransactionItem): string {
  return [
    record.trxId || 'no-trx',
    record.waitingLockId || 'no-wait',
    record.blockingLockId || 'no-blocking-lock',
    record.blockingTrxId || 'no-blocking-trx',
    record.threadId == null ? 'no-thread' : String(record.threadId),
  ].join(':');
}

export enum ActiveTransactionLoadErrorKind {
  PROCESS_PRIVILEGE_REQUIRED = 'PROCESS_PRIVILEGE_REQUIRED',
  OTHER = 'OTHER',
}

export interface ActiveTransactionLoadError {
  kind: ActiveTransactionLoadErrorKind;
  message?: string;
}

export function classifyActiveTransactionLoadError(error: unknown): ActiveTransactionLoadError {
  if (typeof error !== 'object' || error === null) {
    return { kind: ActiveTransactionLoadErrorKind.OTHER };
  }
  const response = error as Record<string, unknown>;
  if (response.errorCode === ACTIVE_TRANSACTION_PROCESS_PRIVILEGE_ERROR_CODE) {
    return { kind: ActiveTransactionLoadErrorKind.PROCESS_PRIVILEGE_REQUIRED };
  }
  return {
    kind: ActiveTransactionLoadErrorKind.OTHER,
    message:
      typeof response.errorMessage === 'string'
        ? response.errorMessage
        : typeof response.message === 'string'
          ? response.message
          : undefined,
  };
}

export function beginActiveTransactionRefresh(generationRef: RequestGenerationRef): number {
  return beginLatestRequest(generationRef);
}

export function invalidateActiveTransactionRefresh(generationRef: RequestGenerationRef): void {
  invalidateLatestRequest(generationRef);
}

export function isLatestActiveTransactionRefresh(generationRef: RequestGenerationRef, generation: number): boolean {
  return isLatestRequest(generationRef, generation);
}

export function getLiveTransactionAge(
  ageSeconds: number | null,
  snapshotAtMs: number,
  nowMs: number,
): number | null {
  if (ageSeconds == null) {
    return null;
  }
  return ageSeconds + Math.max(0, Math.floor((nowMs - snapshotAtMs) / 1000));
}

export function formatActiveTransactionStartedAt(value: number | string | null): string {
  if (value == null || value === '') {
    return '-';
  }
  const timestamp = dayjs(value);
  return timestamp.isValid() ? timestamp.format('YYYY-MM-DD HH:mm:ss') : '-';
}

export interface TransactionConnectionInspection {
  connectionId: number;
  sql: string;
}

export function getTransactionConnectionInspection(
  record: IActiveTransactionItem,
  target: 'owner' | 'blocker',
): TransactionConnectionInspection | null {
  const connectionId = target === 'blocker' ? record.blockingThreadId : record.threadId;
  const sql =
    target === 'blocker' ? record.blockingConnectionInspectionSql : record.connectionInspectionSql;
  const available = target === 'blocker' ? record.canOpenBlockingSession : record.canOpenSession;
  if (!available || connectionId == null || !sql) {
    return null;
  }
  return { connectionId, sql };
}
