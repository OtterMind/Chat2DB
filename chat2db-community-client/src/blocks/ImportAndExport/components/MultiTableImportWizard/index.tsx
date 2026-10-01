import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type ForwardedRef,
} from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  InputNumber,
  Segmented,
  Select,
  Steps,
  Switch,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import { Plus, Trash2 } from 'lucide-react';
import UploadLocalFile, { type FileUrl } from '@/components/UploadLocalFile';
import i18n from '@/i18n';
import sqlService from '@/service/sql';
import type { ImportExportDataBoundInfo } from '@/typings/importExport';
import { getImportPreviewErrorMessage } from '../ImportMappingContent/mapping';
import { stageSelectedImportFile } from '../ImportMappingContent/fileStaging';
import { createStagedFileOwnership } from '../ImportMappingContent/stagedFileOwnership';
import ImportModeControl from '../ImportModeControl';
import type { ImportExportFileRef } from '../ImportExportFile';
import {
  createLogicalDependencyDraft,
  getLogicalDependencyIssues,
  getMultiTableSourceIssues,
  multiTableTargetKey,
  multiTableTargetLabel,
  normalizeLogicalDependencySequences,
  reconcileLogicalDependenciesWithTargets,
  reconcileMultiTableSources,
  reconcileMultiTableSourcesWithTargets,
  type LogicalDependencyDraft,
  type MultiTableSourceDraft,
  type MultiTableTarget,
} from './model';
import {
  buildMultiTableImportParams,
  constrainMultiTableSettings,
  defaultMultiTableImportSettingsFor,
  getMultiTableCycleStrategyLabelKey,
  getMultiTableOnErrorLabelKey,
  hasCompatibleMultiTableSettings,
  hasValidMultiTableMaxErrors,
  hasValidMultiTableNumericSettings,
  requiresImplicitMultiTableStaging,
  requestsAdvancedMultiTableImport,
  supportsDeferredConstraintImport,
  supportsMultiTableIndexRebuild,
  supportsMultiTableSequenceReset,
  supportsMultiTableStaging,
  supportsMultiTableStatisticsRefresh,
  usesMultiTableStaging,
  MULTI_TABLE_IMPORT_ACCEPT,
  MULTI_TABLE_IMPORT_FORMAT,
  type MultiTableImportSettings,
} from './taskParams';
import { createAsyncTaskLimiter } from './uploadQueue';
import { useStyles } from './style';

interface MultiTableImportWizardProps {
  boundInfo: ImportExportDataBoundInfo;
  setIsReady?: (ready: boolean) => void;
}

interface ColumnMetadataState {
  status: 'LOADING' | 'READY' | 'ERROR';
  error?: string;
}

const MAX_IMPORT_FILES = 1000;
const MAX_IMPORT_FILE_SIZE_MEGABYTES = 1024;
const MAX_IMPORT_TOTAL_SIZE_BYTES = 10 * 1024 * 1024 * 1024;

const uniqueTargets = (targets: MultiTableTarget[]) =>
  Array.from(new Map(targets.map((target) => [multiTableTargetKey(target), target])).values()).sort((left, right) =>
    multiTableTargetLabel(left).localeCompare(multiTableTargetLabel(right)),
  );

const MultiTableImportWizard = forwardRef(
  ({ boundInfo, setIsReady }: MultiTableImportWizardProps, ref: ForwardedRef<ImportExportFileRef>) => {
    const { styles } = useStyles();
    const [currentStep, setCurrentStep] = useState(0);
    const [targets, setTargets] = useState<MultiTableTarget[]>([]);
    const [targetsLoading, setTargetsLoading] = useState(true);
    const [targetLoadError, setTargetLoadError] = useState<string>();
    const [sources, setSources] = useState<MultiTableSourceDraft[]>([]);
    const [dependencies, setDependencies] = useState<LogicalDependencyDraft[]>([]);
    const [columnsByTarget, setColumnsByTarget] = useState<Record<string, string[]>>({});
    const [columnMetadataByTarget, setColumnMetadataByTarget] = useState<Record<string, ColumnMetadataState>>({});
    const [settings, setSettings] = useState<MultiTableImportSettings>(() =>
      defaultMultiTableImportSettingsFor(boundInfo.databaseType),
    );
    const sourceSequence = useRef(0);
    const dependencySequence = useRef(0);
    const requestedColumns = useRef(new Set<string>());
    const metadataGeneration = useRef(0);
    const mounted = useRef(true);
    const stagingGeneration = useRef(0);
    const activeSourceIds = useRef(new Set<string>());
    const uploadsInFlight = useRef(new Set<string>());
    const stagedFileIdsBySource = useRef(new Map<string, string>());
    const uploadLimiter = useRef(createAsyncTaskLimiter(3)).current;
    const stagingOwnershipRef = useRef<ReturnType<typeof createStagedFileOwnership>>();
    if (!stagingOwnershipRef.current) {
      stagingOwnershipRef.current = createStagedFileOwnership((fileId) => sqlService.releaseImportFile({ fileId }));
    }
    const stagingOwnership = stagingOwnershipRef.current;
    const dataSourceId = boundInfo.dataSourceId;
    const stagingSupported = supportsMultiTableStaging(boundInfo.databaseType);
    const deferredConstraintsSupported = supportsDeferredConstraintImport(boundInfo.databaseType);
    const sequenceResetSupported = supportsMultiTableSequenceReset(boundInfo.databaseType);
    const indexRebuildSupported = supportsMultiTableIndexRebuild(boundInfo.databaseType);
    const statisticsRefreshSupported = supportsMultiTableStatisticsRefresh(boundInfo.databaseType);
    const implicitStagingRequired = requiresImplicitMultiTableStaging(settings);
    const stagingEnabled = usesMultiTableStaging(settings);
    const maxErrorsInvalid = !hasValidMultiTableMaxErrors(settings);

    const releaseSourceStagedFile = useCallback(
      (sourceId: string, fallbackFileId?: string) => {
        const fileId = stagedFileIdsBySource.current.get(sourceId) || fallbackFileId;
        stagedFileIdsBySource.current.delete(sourceId);
        if (fileId) void stagingOwnership.release(fileId);
      },
      [stagingOwnership],
    );

    const releaseAllStagedFiles = useCallback(() => {
      stagingGeneration.current += 1;
      activeSourceIds.current.clear();
      stagedFileIdsBySource.current.clear();
      void stagingOwnership.releaseAll();
    }, [stagingOwnership]);

    useEffect(() => {
      mounted.current = true;
      return () => {
        mounted.current = false;
        releaseAllStagedFiles();
      };
    }, [releaseAllStagedFiles]);

    useEffect(() => {
      let cancelled = false;
      const generation = ++metadataGeneration.current;
      const noTargets = new Set<string>();
      releaseAllStagedFiles();
      setCurrentStep(0);
      setTargets([]);
      setSources([]);
      setDependencies((current) => reconcileLogicalDependenciesWithTargets(current, noTargets));
      requestedColumns.current.clear();
      setColumnsByTarget({});
      setColumnMetadataByTarget({});
      setSettings((current) => constrainMultiTableSettings(current, boundInfo.databaseType));
      setTargetsLoading(true);
      setTargetLoadError(undefined);
      if (dataSourceId === undefined) {
        setTargetsLoading(false);
        setTargetLoadError(i18n('workspace.importExport.multiTable.tableLoadFailed'));
        return () => {};
      }

      const loadTables = async () => {
        const toTargets = (tables: Array<{ name: string }>, schemaName?: string) =>
          tables.map((table) => ({
            databaseName: boundInfo.databaseName,
            schemaName,
            tableName: table.name,
          }));

        if (boundInfo.targetScope === 'SCHEMA') {
          const tables = await sqlService.getAllTableList({
            dataSourceId,
            databaseName: boundInfo.databaseName,
            schemaName: boundInfo.schemaName,
          });
          return toTargets(tables, boundInfo.schemaName);
        }

        const metadata = await sqlService.getDatabaseSchemaList({ dataSourceId });
        const database = metadata.databases?.find((item) => item.name === boundInfo.databaseName);
        const schemas = database?.schemas || metadata.schemas || [];
        if (!schemas.length) {
          const tables = await sqlService.getAllTableList({
            dataSourceId,
            databaseName: boundInfo.databaseName,
          });
          return toTargets(tables);
        }
        const tableGroups = await Promise.all(
          schemas.map(async ({ name }) => {
            const tables = await sqlService.getAllTableList({
              dataSourceId,
              databaseName: boundInfo.databaseName,
              schemaName: name,
            });
            return toTargets(tables, name);
          }),
        );
        return tableGroups.flat();
      };

      loadTables()
        .then((loadedTargets) => {
          if (!cancelled && generation === metadataGeneration.current) {
            setTargets(uniqueTargets(loadedTargets));
          }
        })
        .catch((error) => {
          if (!cancelled && generation === metadataGeneration.current) {
            setTargetLoadError(
              getImportPreviewErrorMessage(error, i18n('workspace.importExport.multiTable.tableLoadFailed')),
            );
          }
        })
        .finally(() => {
          if (!cancelled && generation === metadataGeneration.current) setTargetsLoading(false);
        });
      return () => {
        cancelled = true;
      };
    }, [
      boundInfo.databaseName,
      boundInfo.databaseType,
      boundInfo.schemaName,
      boundInfo.targetScope,
      dataSourceId,
      releaseAllStagedFiles,
    ]);

    useEffect(() => {
      const validTargetKeys = new Set(targets.map(multiTableTargetKey));
      setSources((current) => reconcileMultiTableSourcesWithTargets(current, targets));
      setDependencies((current) => reconcileLogicalDependenciesWithTargets(current, validTargetKeys));
    }, [targets]);

    useEffect(() => {
      const pendingSources = sources.filter(
        (source) => source.status === 'PENDING' && !uploadsInFlight.current.has(source.id),
      );
      if (!pendingSources.length) return;
      const pendingIds = new Set(pendingSources.map((source) => source.id));
      pendingSources.forEach((source) => uploadsInFlight.current.add(source.id));
      setSources((current) =>
        current.map((source) => (pendingIds.has(source.id) ? { ...source, status: 'STAGING' } : source)),
      );
      pendingSources.forEach((source) => {
        const generation = stagingGeneration.current;
        uploadLimiter(async () => {
          if (
            !mounted.current ||
            stagingGeneration.current !== generation ||
            !activeSourceIds.current.has(source.id)
          ) {
            return undefined;
          }
          return stageSelectedImportFile(
            source.selection,
            sqlService.uploadImportFile,
            sqlService.stageDesktopImportFile,
          );
        })
          .then((fileId) => {
            if (!fileId) return;
            stagingOwnership.own(fileId);
            if (
              !mounted.current ||
              stagingGeneration.current !== generation ||
              !activeSourceIds.current.has(source.id)
            ) {
              void stagingOwnership.release(fileId);
              return;
            }
            stagedFileIdsBySource.current.set(source.id, fileId);
            setSources((current) =>
              current.map((item) =>
                item.id === source.id ? { ...item, fileId, status: 'READY', error: undefined } : item,
              ),
            );
          })
          .catch((error) => {
            if (
              !mounted.current ||
              stagingGeneration.current !== generation ||
              !activeSourceIds.current.has(source.id)
            ) {
              return;
            }
            const message = getImportPreviewErrorMessage(error, i18n('common.text.failure'));
            setSources((current) =>
              current.map((item) => (item.id === source.id ? { ...item, status: 'ERROR', error: message } : item)),
            );
          })
          .finally(() => uploadsInFlight.current.delete(source.id));
      });
    }, [sources, stagingOwnership, uploadLimiter]);

    const targetByKey = useMemo(
      () => new Map(targets.map((target) => [multiTableTargetKey(target), target])),
      [targets],
    );
    const selectedTargetKeys = useMemo(
      () => new Set(sources.map((source) => source.targetKey).filter((key): key is string => !!key)),
      [sources],
    );
    const selectedTargets = useMemo(
      () =>
        Array.from(selectedTargetKeys)
          .map((key) => targetByKey.get(key))
          .filter((target): target is MultiTableTarget => !!target),
      [selectedTargetKeys, targetByKey],
    );

    const dependencyTargets = useMemo(() => {
      const keys = new Set<string>();
      dependencies.forEach((dependency) => {
        if (dependency.parentTargetKey && selectedTargetKeys.has(dependency.parentTargetKey)) {
          keys.add(dependency.parentTargetKey);
        }
        if (dependency.childTargetKey && selectedTargetKeys.has(dependency.childTargetKey)) {
          keys.add(dependency.childTargetKey);
        }
      });
      return Array.from(keys)
        .map((key) => targetByKey.get(key))
        .filter((target): target is MultiTableTarget => !!target);
    }, [dependencies, selectedTargetKeys, targetByKey]);

    const loadTargetColumns = useCallback(
      (target: MultiTableTarget) => {
        if (dataSourceId === undefined) return;
        const generation = metadataGeneration.current;
        const targetKey = multiTableTargetKey(target);
        if (requestedColumns.current.has(targetKey)) return;
        requestedColumns.current.add(targetKey);
        setColumnMetadataByTarget((current) => ({ ...current, [targetKey]: { status: 'LOADING' } }));
        sqlService
          .getAllFieldByTable({
            dataSourceId,
            databaseName: target.databaseName || boundInfo.databaseName,
            schemaName: target.schemaName,
            tableName: target.tableName,
          })
          .then((columns) => {
            if (!mounted.current || generation !== metadataGeneration.current) return;
            setColumnsByTarget((current) => ({
              ...current,
              [targetKey]: Array.from(new Set(columns.map((column) => column.name))).sort(),
            }));
            setColumnMetadataByTarget((current) => ({ ...current, [targetKey]: { status: 'READY' } }));
          })
          .catch((error) => {
            if (!mounted.current || generation !== metadataGeneration.current) return;
            requestedColumns.current.delete(targetKey);
            setColumnsByTarget((current) => ({ ...current, [targetKey]: [] }));
            setColumnMetadataByTarget((current) => ({
              ...current,
              [targetKey]: {
                status: 'ERROR',
                error: getImportPreviewErrorMessage(error, i18n('workspace.importExport.multiTable.columnLoadFailed')),
              },
            }));
          });
      },
      [boundInfo.databaseName, dataSourceId],
    );

    useEffect(() => {
      dependencyTargets.forEach(loadTargetColumns);
    }, [dependencyTargets, loadTargetColumns]);

    const columnLoadFailures = useMemo(
      () =>
        dependencyTargets
          .map((target) => ({
            target,
            targetKey: multiTableTargetKey(target),
            state: columnMetadataByTarget[multiTableTargetKey(target)],
          }))
          .filter(({ state }) => state?.status === 'ERROR'),
      [columnMetadataByTarget, dependencyTargets],
    );

    const validTargetKeys = useMemo(() => new Set(targetByKey.keys()), [targetByKey]);
    const sourceIssues = getMultiTableSourceIssues(sources, validTargetKeys);
    const dependencyIssues = getLogicalDependencyIssues(dependencies, selectedTargetKeys);
    const mappingReady =
      !targetsLoading &&
      !targetLoadError &&
      sourceIssues.ready &&
      !sourceIssues.invalidTargets.length &&
      !sourceIssues.duplicateTargets &&
      !sourceIssues.failed.length;
    const dependenciesReady =
      !dependencyIssues.incomplete &&
      !dependencyIssues.unknownTarget &&
      !dependencyIssues.duplicate &&
      !dependencyIssues.invalidGroup;
    const wizardReady = currentStep === 2 && mappingReady && dependenciesReady;
    const settingsReady =
      hasValidMultiTableNumericSettings(settings) &&
      hasCompatibleMultiTableSettings(settings, boundInfo.databaseType) &&
      (!requestsAdvancedMultiTableImport(settings) || stagingSupported) &&
      (settings.cycleStrategy !== 'DEFER_CONSTRAINTS' || deferredConstraintsSupported);

    useEffect(() => {
      setIsReady?.(wizardReady && settingsReady);
    }, [setIsReady, settingsReady, wizardReady]);

    useImperativeHandle(ref, () => ({
      invalidateStagedFiles: (message: string) => {
        releaseAllStagedFiles();
        setCurrentStep(0);
        setSources((current) =>
          current.map((source) => ({
            ...source,
            fileId: undefined,
            status: 'ERROR',
            error: message,
          })),
        );
        setIsReady?.(false);
      },
      releaseStagedFiles: () => {
        releaseAllStagedFiles();
        setIsReady?.(false);
      },
      markStagedFilesSubmitted: (fileIds: readonly string[]) => {
        stagingGeneration.current += 1;
        activeSourceIds.current.clear();
        stagedFileIdsBySource.current.clear();
        stagingOwnership.transfer(fileIds);
      },
      getValues: (clientSubmissionId?: string) =>
        buildMultiTableImportParams({
          boundInfo,
          clientSubmissionId,
          sources,
          targets,
          dependencies,
          settings,
        }),
    }));

    const targetOptions = useMemo(
      () => targets.map((target) => ({ label: multiTableTargetLabel(target), value: multiTableTargetKey(target) })),
      [targets],
    );
    const selectedTargetOptions = useMemo(
      () =>
        selectedTargets.map((target) => ({
          label: multiTableTargetLabel(target),
          value: multiTableTargetKey(target),
        })),
      [selectedTargets],
    );

    const handleSelectionsChange = (selections: FileUrl[]) => {
      setCurrentStep(0);
      const nextSources = reconcileMultiTableSources(
        sources,
        selections,
        targets,
        () => `source-${++sourceSequence.current}`,
      );
      const nextSourceIds = new Set(nextSources.map((source) => source.id));
      activeSourceIds.current = nextSourceIds;
      sources
        .filter((source) => !nextSourceIds.has(source.id))
        .forEach((source) => releaseSourceStagedFile(source.id, source.fileId));
      setSources(nextSources);
    };

    const addDependencyGroup = () => {
      const id = `dependency-${++dependencySequence.current}`;
      setDependencies((current) => [...current, createLogicalDependencyDraft(id)]);
    };

    const addDependencyColumn = (dependency: LogicalDependencyDraft) => {
      const id = `dependency-${++dependencySequence.current}`;
      setDependencies((current) => {
        const lastGroupIndex = current.reduce(
          (lastIndex, item, index) => (item.constraintName === dependency.constraintName ? index : lastIndex),
          -1,
        );
        const column = {
          ...createLogicalDependencyDraft(id),
          constraintName: dependency.constraintName,
          parentTargetKey: dependency.parentTargetKey,
          childTargetKey: dependency.childTargetKey,
        };
        const insertionIndex = lastGroupIndex < 0 ? current.length : lastGroupIndex + 1;
        return normalizeLogicalDependencySequences([
          ...current.slice(0, insertionIndex),
          column,
          ...current.slice(insertionIndex),
        ]);
      });
    };

    const removeDependency = (id: string) => {
      setDependencies((current) =>
        normalizeLogicalDependencySequences(current.filter((dependency) => dependency.id !== id)),
      );
    };

    const updateDependency = (id: string, patch: Partial<LogicalDependencyDraft>) => {
      setDependencies((current) => {
        const selected = current.find((dependency) => dependency.id === id);
        if (!selected) return current;
        const changesParentTarget = Object.prototype.hasOwnProperty.call(patch, 'parentTargetKey');
        const changesChildTarget = Object.prototype.hasOwnProperty.call(patch, 'childTargetKey');
        return current.map((dependency) => {
          if (dependency.id === id) return { ...dependency, ...patch };
          if (!selected.constraintName || dependency.constraintName !== selected.constraintName) return dependency;
          return {
            ...dependency,
            ...(changesParentTarget ? { parentTargetKey: patch.parentTargetKey, parentColumn: undefined } : {}),
            ...(changesChildTarget ? { childTargetKey: patch.childTargetKey, childColumn: undefined } : {}),
          };
        });
      });
    };

    const updateSetting = <K extends keyof MultiTableImportSettings>(key: K, value: MultiTableImportSettings[K]) =>
      setSettings((current) => ({ ...current, [key]: value }));

    const statusTag = (source: MultiTableSourceDraft) => {
      const status = {
        PENDING: { color: 'default', label: i18n('workspace.importExport.multiTable.statusPending') },
        STAGING: { color: 'processing', label: i18n('workspace.importExport.multiTable.statusStaging') },
        READY: { color: 'success', label: i18n('workspace.importExport.multiTable.statusReady') },
        ERROR: { color: 'error', label: i18n('workspace.importExport.multiTable.statusFailed') },
      }[source.status];
      const tag = (
        <Tag
          aria-label={source.error ? `${status.label}: ${source.error}` : status.label}
          color={status.color}
          tabIndex={source.error ? 0 : undefined}
        >
          {status.label}
        </Tag>
      );
      return source.error ? <Tooltip title={source.error}>{tag}</Tooltip> : tag;
    };

    const renderSourceStep = () => (
      <div className={styles.stepBody}>
        <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.filesAndTables')}</div>
        <UploadLocalFile
          key={[dataSourceId, boundInfo.databaseName, boundInfo.schemaName, boundInfo.targetScope].join(':')}
          accept={MULTI_TABLE_IMPORT_ACCEPT}
          multiple
          fileSize={MAX_IMPORT_FILE_SIZE_MEGABYTES}
          maxFiles={MAX_IMPORT_FILES}
          maxTotalSizeBytes={MAX_IMPORT_TOTAL_SIZE_BYTES}
          fileUrlListChange={handleSelectionsChange}
          description={[i18n('workspace.importExport.multiTable.selectFiles'), MULTI_TABLE_IMPORT_FORMAT]}
        />
        {targetLoadError && <Alert type="error" showIcon message={targetLoadError} />}
        <Table<MultiTableSourceDraft>
          className={styles.sourceTable}
          size="small"
          rowKey="id"
          pagination={false}
          loading={targetsLoading}
          scroll={{ x: 720, y: 220 }}
          dataSource={sources}
          locale={{ emptyText: i18n('workspace.importExport.multiTable.noFiles') }}
          columns={[
            {
              title: i18n('workspace.importExport.multiTable.file'),
              dataIndex: 'displayFileName',
              ellipsis: true,
            },
            {
              title: i18n('workspace.importExport.multiTable.stagingStatus'),
              width: 120,
              render: (_, source) => statusTag(source),
            },
            {
              title: i18n('workspace.importExport.multiTable.targetTable'),
              width: 300,
              render: (_, source) => (
                <Select
                  aria-label={`${i18n('workspace.importExport.multiTable.targetTable')}: ${source.displayFileName}`}
                  showSearch
                  optionFilterProp="label"
                  placeholder={i18n('workspace.importExport.multiTable.selectTargetTable')}
                  value={source.targetKey}
                  options={targetOptions}
                  status={sourceIssues.duplicateTargets ? 'error' : undefined}
                  onChange={(targetKey) =>
                    setSources((current) =>
                      current.map((item) => (item.id === source.id ? { ...item, targetKey } : item)),
                    )
                  }
                />
              ),
            },
          ]}
        />
        {sourceIssues.duplicateTargets && (
          <Alert type="error" showIcon message={i18n('workspace.importExport.multiTable.duplicateTarget')} />
        )}
        {!!sourceIssues.failed.length && (
          <Alert type="error" showIcon message={i18n('workspace.importExport.multiTable.stagingFailed')} />
        )}
      </div>
    );

    const renderDependencyStep = () => {
      const thirdParty = settings.sourceKind === 'THIRD_PARTY';
      return (
        <div className={styles.stepBody}>
          {!stagingSupported && (
            <Alert type="info" showIcon message={i18n('workspace.importExport.multiTable.advancedUnsupported')} />
          )}
          <section className={styles.section}>
            <div id="multi-table-source-kind-label" className={styles.sectionTitle}>
              {i18n('workspace.importExport.multiTable.sourceKind')}
            </div>
            <Segmented
              aria-labelledby="multi-table-source-kind-label"
              block
              value={settings.sourceKind}
              options={[
                { label: i18n('workspace.importExport.multiTable.sourceTrusted'), value: 'TRUSTED' },
                {
                  label: i18n('workspace.importExport.multiTable.sourceThirdParty'),
                  value: 'THIRD_PARTY',
                  disabled: !stagingSupported,
                },
              ]}
              onChange={(value) => updateSetting('sourceKind', value as MultiTableImportSettings['sourceKind'])}
            />
            {thirdParty && (
              <Alert
                type="warning"
                showIcon
                message={i18n('workspace.importExport.multiTable.thirdPartyForcedChecks')}
              />
            )}
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.cycleStrategy')}</div>
            <Select
              aria-label={i18n('workspace.importExport.multiTable.cycleStrategy')}
              value={settings.cycleStrategy}
              options={[
                { label: i18n(getMultiTableCycleStrategyLabelKey('REJECT')), value: 'REJECT' },
                {
                  label: i18n(getMultiTableCycleStrategyLabelKey('DEFER_CONSTRAINTS')),
                  value: 'DEFER_CONSTRAINTS',
                  disabled: !deferredConstraintsSupported,
                },
                {
                  label: i18n(getMultiTableCycleStrategyLabelKey('STAGING_TWO_PHASE')),
                  value: 'STAGING_TWO_PHASE',
                  disabled: !stagingSupported,
                },
              ]}
              onChange={(value) => updateSetting('cycleStrategy', value)}
            />
            <div className={styles.switchGrid}>
              <label className={styles.switchRow}>
                <span>{i18n('workspace.importExport.multiTable.enableStaging')}</span>
                <Switch
                  checked={stagingEnabled}
                  disabled={!stagingSupported || implicitStagingRequired}
                  onChange={(enabled) =>
                    updateSetting('stagingPolicy', {
                      ...settings.stagingPolicy,
                      enabled,
                      allVarchar: enabled ? settings.stagingPolicy.allVarchar : false,
                    })
                  }
                />
              </label>
              <label className={styles.switchRow}>
                <span>{i18n('workspace.importExport.multiTable.allVarcharStaging')}</span>
                <Switch
                  checked={settings.stagingPolicy.allVarchar || thirdParty}
                  disabled={!stagingSupported || thirdParty || !stagingEnabled}
                  onChange={(allVarchar) => updateSetting('stagingPolicy', { ...settings.stagingPolicy, allVarchar })}
                />
              </label>
            </div>
            {implicitStagingRequired && !thirdParty && (
              <Alert
                type="info"
                showIcon
                message={i18n('workspace.importExport.multiTable.stagingRequiredByOptions')}
              />
            )}
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.executionMode')}</div>
            <ImportModeControl
              value={settings.mode}
              onChange={(mode) => updateSetting('mode', mode)}
            />
          </section>

          <section className={styles.section}>
            <div className={styles.sectionHeader}>
              <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.logicalDependencies')}</div>
              <Button size="small" icon={<Plus size={15} />} onClick={addDependencyGroup}>
                {i18n('workspace.importExport.multiTable.addDependency')}
              </Button>
            </div>
            {!dependencies.length && (
              <div className={styles.emptyHint}>{i18n('workspace.importExport.multiTable.noLogicalDependencies')}</div>
            )}
            {dependencies.map((dependency) => {
              const parentColumnState = columnMetadataByTarget[dependency.parentTargetKey || ''];
              const childColumnState = columnMetadataByTarget[dependency.childTargetKey || ''];
              const dependencyLabel = `${dependency.constraintName || dependency.id} #${dependency.keySequence || 1}`;
              return (
                <div className={styles.dependencyRow} key={dependency.id}>
                  <Select
                    aria-label={`${i18n('workspace.importExport.multiTable.parentTable')}: ${dependencyLabel}`}
                    showSearch
                    optionFilterProp="label"
                    placeholder={i18n('workspace.importExport.multiTable.parentTable')}
                    value={dependency.parentTargetKey}
                    options={selectedTargetOptions}
                    onChange={(parentTargetKey) =>
                      updateDependency(dependency.id, { parentTargetKey, parentColumn: undefined })
                    }
                  />
                  <Select
                    aria-label={`${i18n('workspace.importExport.multiTable.parentColumn')}: ${dependencyLabel}`}
                    disabled={!dependency.parentTargetKey || parentColumnState?.status === 'ERROR'}
                    loading={parentColumnState?.status === 'LOADING'}
                    showSearch
                    status={parentColumnState?.status === 'ERROR' ? 'error' : undefined}
                    placeholder={i18n('workspace.importExport.multiTable.parentColumn')}
                    value={dependency.parentColumn}
                    options={(columnsByTarget[dependency.parentTargetKey || ''] || []).map((column) => ({
                      label: column,
                      value: column,
                    }))}
                    onChange={(parentColumn) => updateDependency(dependency.id, { parentColumn })}
                  />
                  <Select
                    aria-label={`${i18n('workspace.importExport.multiTable.childTable')}: ${dependencyLabel}`}
                    showSearch
                    optionFilterProp="label"
                    placeholder={i18n('workspace.importExport.multiTable.childTable')}
                    value={dependency.childTargetKey}
                    options={selectedTargetOptions}
                    onChange={(childTargetKey) =>
                      updateDependency(dependency.id, { childTargetKey, childColumn: undefined })
                    }
                  />
                  <Select
                    aria-label={`${i18n('workspace.importExport.multiTable.childColumn')}: ${dependencyLabel}`}
                    disabled={!dependency.childTargetKey || childColumnState?.status === 'ERROR'}
                    loading={childColumnState?.status === 'LOADING'}
                    showSearch
                    status={childColumnState?.status === 'ERROR' ? 'error' : undefined}
                    placeholder={i18n('workspace.importExport.multiTable.childColumn')}
                    value={dependency.childColumn}
                    options={(columnsByTarget[dependency.childTargetKey || ''] || []).map((column) => ({
                      label: column,
                      value: column,
                    }))}
                    onChange={(childColumn) => updateDependency(dependency.id, { childColumn })}
                  />
                  <div className={styles.dependencyActions}>
                    <Tag bordered={false}>#{dependency.keySequence}</Tag>
                    <Tooltip title={i18n('workspace.importExport.multiTable.addDependencyColumn')}>
                      <Button
                        aria-label={`${i18n(
                          'workspace.importExport.multiTable.addDependencyColumn',
                        )}: ${dependencyLabel}`}
                        type="text"
                        icon={<Plus size={16} />}
                        onClick={() => addDependencyColumn(dependency)}
                      />
                    </Tooltip>
                    <Tooltip title={i18n('common.button.delete')}>
                      <Button
                        aria-label={`${i18n('common.button.delete')}: ${dependencyLabel}`}
                        type="text"
                        danger
                        icon={<Trash2 size={16} />}
                        onClick={() => removeDependency(dependency.id)}
                      />
                    </Tooltip>
                  </div>
                </div>
              );
            })}
            {columnLoadFailures.map(({ target, targetKey, state }) => (
              <Alert
                key={targetKey}
                type="error"
                showIcon
                message={`${multiTableTargetLabel(target)}: ${state.error}`}
                action={
                  <Button size="small" onClick={() => loadTargetColumns(target)}>
                    {i18n('workspace.importExport.multiTable.retryColumnLoad')}
                  </Button>
                }
              />
            ))}
            {(dependencyIssues.incomplete ||
              dependencyIssues.unknownTarget ||
              dependencyIssues.duplicate ||
              dependencyIssues.invalidGroup) && (
              <Alert
                type="error"
                showIcon
                message={
                  dependencyIssues.duplicate
                    ? i18n('workspace.importExport.multiTable.duplicateDependency')
                    : i18n('workspace.importExport.multiTable.invalidDependency')
                }
              />
            )}
          </section>
        </div>
      );
    };

    const toggleValidation = (key: keyof MultiTableImportSettings['validationOptions'], checked: boolean) =>
      updateSetting('validationOptions', { ...settings.validationOptions, [key]: checked });
    const toggleFinalization = (key: keyof MultiTableImportSettings['finalizationOptions'], checked: boolean) =>
      updateSetting('finalizationOptions', { ...settings.finalizationOptions, [key]: checked });
    const finalizationLocked = settings.rollbackOptions.fullRollback && !settings.rollbackOptions.rehearsal;

    const renderValidationStep = () => {
      const thirdParty = settings.sourceKind === 'THIRD_PARTY';
      return (
        <div className={styles.stepBody}>
          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.validation')}</div>
            <div className={styles.checkboxGrid}>
              <Checkbox
                checked={settings.validationOptions.sourceProfiling || thirdParty}
                disabled={!stagingSupported || thirdParty}
                onChange={(event) => toggleValidation('sourceProfiling', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.sourceProfiling')}
              </Checkbox>
              <Checkbox
                checked={settings.validationOptions.rowCount}
                disabled={!stagingSupported}
                onChange={(event) => toggleValidation('rowCount', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.rowCountCheck')}
              </Checkbox>
              <Checkbox
                checked={settings.validationOptions.checksum}
                disabled={!stagingSupported}
                onChange={(event) => toggleValidation('checksum', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.checksumCheck')}
              </Checkbox>
              <Checkbox
                checked={settings.validationOptions.orphanCheck || thirdParty}
                disabled={!stagingSupported || thirdParty}
                onChange={(event) => toggleValidation('orphanCheck', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.orphanCheck')}
              </Checkbox>
            </div>
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.errorHandling')}</div>
            <div className={styles.inlineControls}>
              <Select
                aria-label={i18n('workspace.importExport.onError')}
                value={settings.onError}
                options={[
                  { label: i18n(getMultiTableOnErrorLabelKey('ABORT')), value: 'ABORT' },
                  { label: i18n(getMultiTableOnErrorLabelKey('SKIP')), value: 'SKIP' },
                ]}
                onChange={(onError) =>
                  setSettings((current) => ({
                    ...current,
                    onError,
                    maxErrors: onError === 'SKIP' ? current.maxErrors || 100 : undefined,
                  }))
                }
              />
              {settings.onError === 'SKIP' && (
                <div className={styles.fieldWithError}>
                  <InputNumber
                    aria-label={i18n('workspace.importExport.maxErrors')}
                    aria-describedby={maxErrorsInvalid ? 'multi-table-max-errors-error' : undefined}
                    aria-invalid={maxErrorsInvalid}
                    min={1}
                    precision={0}
                    status={maxErrorsInvalid ? 'error' : undefined}
                    step={1}
                    value={settings.maxErrors}
                    placeholder={i18n('workspace.importExport.maxErrors')}
                    onChange={(value) => updateSetting('maxErrors', value ?? undefined)}
                  />
                  {maxErrorsInvalid && (
                    <div id="multi-table-max-errors-error" className={styles.fieldError} role="alert">
                      {i18n('workspace.importExport.multiTable.maxErrorsInvalid')}
                    </div>
                  )}
                </div>
              )}
            </div>
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.finalization')}</div>
            <div className={styles.checkboxGrid}>
              <Checkbox
                checked={settings.finalizationOptions.resetSequences}
                disabled={!sequenceResetSupported || finalizationLocked}
                onChange={(event) => toggleFinalization('resetSequences', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.resetSequences')}
              </Checkbox>
              <Checkbox
                checked={settings.finalizationOptions.rebuildIndexes}
                disabled={!indexRebuildSupported || finalizationLocked}
                onChange={(event) => toggleFinalization('rebuildIndexes', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.rebuildIndexes')}
              </Checkbox>
              <Checkbox
                checked={settings.finalizationOptions.refreshStatistics}
                disabled={!statisticsRefreshSupported || finalizationLocked}
                onChange={(event) => toggleFinalization('refreshStatistics', event.target.checked)}
              >
                {i18n('workspace.importExport.multiTable.refreshStatistics')}
              </Checkbox>
            </div>
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.rollbackAndRehearsal')}</div>
            <div className={styles.switchGrid}>
              <label className={styles.switchRow}>
                <span>{i18n('workspace.importExport.multiTable.fullRollback')}</span>
                <Switch
                  checked={settings.rollbackOptions.fullRollback}
                  disabled={!stagingSupported}
                  onChange={(fullRollback) =>
                    setSettings((current) => ({
                      ...current,
                      rollbackOptions: { ...current.rollbackOptions, fullRollback },
                      ...(fullRollback && !current.rollbackOptions.rehearsal
                        ? {
                            finalizationOptions: {
                              resetSequences: false,
                              rebuildIndexes: false,
                              refreshStatistics: false,
                            },
                          }
                        : {}),
                    }))
                  }
                />
              </label>
              <label className={styles.switchRow}>
                <span>{i18n('workspace.importExport.multiTable.performanceRehearsal')}</span>
                <Switch
                  checked={settings.rollbackOptions.rehearsal}
                  disabled={!stagingSupported}
                  onChange={(rehearsal) =>
                    setSettings((current) => ({
                      ...current,
                      rollbackOptions: { ...current.rollbackOptions, rehearsal },
                      ...(!rehearsal && current.rollbackOptions.fullRollback
                        ? {
                            finalizationOptions: {
                              resetSequences: false,
                              rebuildIndexes: false,
                              refreshStatistics: false,
                            },
                          }
                        : {}),
                    }))
                  }
                />
              </label>
            </div>
            {settings.rollbackOptions.rehearsal && (
              <div className={styles.samplePercent}>
                <span>{i18n('workspace.importExport.multiTable.samplePercent')}</span>
                <InputNumber
                  aria-label={i18n('workspace.importExport.multiTable.samplePercent')}
                  min={1}
                  max={100}
                  precision={0}
                  step={1}
                  value={settings.performanceSamplePercent}
                  formatter={(value) => `${value}%`}
                  parser={(value) => Number(value?.replace('%', '') || 5)}
                  onChange={(value) => updateSetting('performanceSamplePercent', value || 5)}
                />
              </div>
            )}
          </section>

          <section className={styles.section}>
            <div className={styles.sectionTitle}>{i18n('workspace.importExport.multiTable.review')}</div>
            <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.fileCount')}>
                {sources.length}
              </Descriptions.Item>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.tableCount')}>
                {selectedTargetKeys.size}
              </Descriptions.Item>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.dependencyCount')}>
                {dependencies.length}
              </Descriptions.Item>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.sourceKind')}>
                {settings.sourceKind === 'THIRD_PARTY'
                  ? i18n('workspace.importExport.multiTable.sourceThirdParty')
                  : i18n('workspace.importExport.multiTable.sourceTrusted')}
              </Descriptions.Item>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.staging')}>
                {stagingEnabled
                  ? i18n('workspace.importExport.multiTable.enabled')
                  : i18n('workspace.importExport.multiTable.disabled')}
              </Descriptions.Item>
              <Descriptions.Item label={i18n('workspace.importExport.multiTable.cycleStrategy')}>
                {i18n(getMultiTableCycleStrategyLabelKey(settings.cycleStrategy))}
              </Descriptions.Item>
            </Descriptions>
          </section>
        </div>
      );
    };

    const canContinue = currentStep === 0 ? mappingReady : dependenciesReady;

    return (
      <div className={styles.wizard}>
        <Steps
          size="small"
          current={currentStep}
          items={[
            { title: i18n('workspace.importExport.multiTable.stepSources') },
            { title: i18n('workspace.importExport.multiTable.stepDependencies') },
            { title: i18n('workspace.importExport.multiTable.stepValidation') },
          ]}
        />
        <div data-testid="multi-table-step-sources" hidden={currentStep !== 0}>
          {renderSourceStep()}
        </div>
        <div data-testid="multi-table-step-dependencies" hidden={currentStep !== 1}>
          {renderDependencyStep()}
        </div>
        <div data-testid="multi-table-step-validation" hidden={currentStep !== 2}>
          {renderValidationStep()}
        </div>
        <div className={styles.navigation}>
          <Button disabled={currentStep === 0} onClick={() => setCurrentStep((step) => Math.max(0, step - 1))}>
            {i18n('common.button.prev')}
          </Button>
          {currentStep < 2 && (
            <Button
              type="primary"
              disabled={!canContinue}
              onClick={() => setCurrentStep((step) => Math.min(2, step + 1))}
            >
              {i18n('common.button.next')}
            </Button>
          )}
        </div>
      </div>
    );
  },
);

export default MultiTableImportWizard;
