import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, RotateCcw } from 'lucide-react';

import { Button } from './ui/button';
import { Input } from './ui/input';
import { Textarea } from './ui/textarea';
import { Badge } from './ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select';
import { describeError } from '../services/auth';
import {
  documentsApi,
  BulkRollbackResponse,
  DocumentRollbackResult,
} from '../services/documents';

/**
 * The dialog that runs a bulk rollback over a collection of documents.
 *
 * <p>The collection is selected by an explicit list of document ids, by
 * repository, by documentation type, or by all three combined. With none
 * of them the collection is every document of the workspace — which is
 * why the dialog always states the size of the collection before the
 * batch is sent.
 *
 * <p>The batch is partially successful by design: a document that cannot
 * be rolled back (never published, target version never approved) is
 * reported in the result list rather than aborting the whole batch. The
 * dialog therefore shows the per-document outcome instead of a single
 * success/failure message.
 */
interface BulkRollbackDialogProps {
  workspaceId: string;
  /** The explicitly selected document ids, if any. */
  documentIds?: string[];
  /** The size of the collection, shown in the confirmation. */
  collectionSize?: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called after a batch that rolled at least one document back. */
  onDone?: (response: BulkRollbackResponse) => void;
}

export function BulkRollbackDialog({
  workspaceId,
  documentIds,
  collectionSize,
  open,
  onOpenChange,
  onDone,
}: BulkRollbackDialogProps) {
  const { t } = useTranslation();
  const [docType, setDocType] = useState<string>('none');
  const [repositoryId, setRepositoryId] = useState<string>('');
  const [strategy, setStrategy] = useState<'PREVIOUS_PUBLISHED' | 'VERSION_NUMBER'>(
    'PREVIOUS_PUBLISHED',
  );
  const [targetVersionNumber, setTargetVersionNumber] = useState<string>('');
  const [reason, setReason] = useState<string>('');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkRollbackResponse | null>(null);

  /**
   * The documents the batch will touch, counted from the selection the
   * caller made. Only an explicit selection has a known size — a
   * repository or a doc-type selection is resolved by the backend.
   */
  const selectedCount = documentIds?.length ?? collectionSize ?? 0;

  async function run() {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const payload: Parameters<typeof documentsApi.bulkRollback>[1] = {
        strategy,
      };
      if (documentIds && documentIds.length > 0) payload.documentIds = documentIds;
      if (repositoryId.trim()) payload.repositoryId = repositoryId.trim();
      if (docType && docType !== 'none') payload.docType = docType;
      if (strategy === 'VERSION_NUMBER') {
        const parsed = Number.parseInt(targetVersionNumber, 10);
        if (Number.isNaN(parsed)) {
          setError(t('bulkRollback.invalidVersion'));
          setRunning(false);
          return;
        }
        payload.targetVersionNumber = parsed;
      }
      if (reason.trim()) payload.reason = reason.trim();

      const response = await documentsApi.bulkRollback(workspaceId, payload);
      setResult(response);
      onDone?.(response);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setRunning(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('bulkRollback.title')}</DialogTitle>
          <DialogDescription>{t('bulkRollback.description')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {selectedCount > 0 && (
            <p className="text-sm text-muted-foreground">
              {t('bulkRollback.selectedCount', { count: selectedCount })}
            </p>
          )}

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1">
              <label className="text-sm font-medium" htmlFor="bulk-rollback-doctype">
                {t('bulkRollback.docType')}
              </label>
              <Select value={docType} onValueChange={setDocType}>
                <SelectTrigger id="bulk-rollback-doctype">
                  <SelectValue placeholder={t('bulkRollback.docTypeNone')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">{t('bulkRollback.docTypeNone')}</SelectItem>
                  {['README', 'API_REFERENCE', 'ARCHITECTURE', 'ONBOARDING_GUIDE'].map((dt) => (
                    <SelectItem key={dt} value={dt}>{dt}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <label className="text-sm font-medium" htmlFor="bulk-rollback-repository">
                {t('bulkRollback.repositoryId')}
              </label>
              <Input
                id="bulk-rollback-repository"
                value={repositoryId}
                onChange={(e) => setRepositoryId(e.target.value)}
                placeholder={t('bulkRollback.repositoryPlaceholder')}
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-sm font-medium" htmlFor="bulk-rollback-strategy">
              {t('bulkRollback.strategy')}
            </label>
            <Select
              value={strategy}
              onValueChange={(v) => setStrategy(v as 'PREVIOUS_PUBLISHED' | 'VERSION_NUMBER')}
            >
              <SelectTrigger id="bulk-rollback-strategy">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="PREVIOUS_PUBLISHED">
                  {t('bulkRollback.strategyPrevious')}
                </SelectItem>
                <SelectItem value="VERSION_NUMBER">
                  {t('bulkRollback.strategyVersionNumber')}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>

          {strategy === 'VERSION_NUMBER' && (
            <div className="space-y-1">
              <label className="text-sm font-medium" htmlFor="bulk-rollback-version">
                {t('bulkRollback.targetVersion')}
              </label>
              <Input
                id="bulk-rollback-version"
                type="number"
                value={targetVersionNumber}
                onChange={(e) => setTargetVersionNumber(e.target.value)}
                placeholder={t('bulkRollback.targetVersionPlaceholder')}
              />
            </div>
          )}

          <div className="space-y-1">
            <label className="text-sm font-medium" htmlFor="bulk-rollback-reason">
              {t('bulkRollback.reason')}
            </label>
            <Textarea
              id="bulk-rollback-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t('bulkRollback.reasonPlaceholder')}
              rows={2}
            />
          </div>

          <p className="flex items-start gap-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-3 text-sm text-amber-700 dark:text-amber-300">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            {t('bulkRollback.draftNotice')}
          </p>

          {error && (
            <p className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              {error}
            </p>
          )}

          {result && (
            <div className="space-y-2 rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Badge variant="success">
                  {t('bulkRollback.rolledBackCount', { count: result.rolledBack })}
                </Badge>
                {result.skipped > 0 && (
                  <Badge variant="warning">
                    {t('bulkRollback.skippedCount', { count: result.skipped })}
                  </Badge>
                )}
              </div>
              <ul className="max-h-40 space-y-1 overflow-auto text-xs text-muted-foreground">
                {result.results.map((r: DocumentRollbackResult) => (
                  <li key={r.documentId}>
                    <span className="font-medium text-foreground">{r.documentTitle}</span>
                    {r.rolledBack
                      ? ` — ${t('bulkRollback.restoredFrom', { version: r.restoredFromVersion ?? 0 })}`
                      : ` — ${r.reason ?? t('bulkRollback.skipped')}`}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={running}>
            {t('common.cancel', 'Cancel')}
          </Button>
          <Button onClick={() => void run()} disabled={running}>
            <RotateCcw className="mr-1 h-4 w-4" />
            {running ? t('bulkRollback.running') : t('bulkRollback.run')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
