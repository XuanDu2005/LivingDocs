import { useEffect, useState } from 'react';
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from './ui/dialog';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { LoadingState, ErrorState } from './ui/states';
import { format } from 'date-fns';
import { describeError } from '../services/auth';
import { templatesApi, DocTemplate } from '../services/templates';
import { Copy, RotateCcw } from 'lucide-react';

interface VersionHistoryDialogProps {
  workspaceId: string;
  templateId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRollback?: () => void;
}

export function VersionHistoryDialog({
  workspaceId, templateId, open, onOpenChange, onRollback,
}: VersionHistoryDialogProps) {
  const [versions, setVersions] = useState<DocTemplate[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  useEffect(() => {
    if (!open || !templateId) return;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const list = await templatesApi.listVersions(workspaceId, templateId);
        setVersions(list);
      } catch (err) {
        setError(describeError(err));
      } finally {
        setLoading(false);
      }
    })();
  }, [open, templateId, workspaceId]);

  async function rollback(version: number) {
    if (!templateId) return;
    if (!confirm(`Roll back to version ${version}? This creates a new version with the older content.`)) return;
    setBusy(version);
    try {
      await templatesApi.rollback(workspaceId, templateId, version);
      onRollback?.();
      onOpenChange(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function cloneTemplate() {
    if (!templateId) return;
    const newName = prompt('Enter name for the cloned template:');
    if (!newName) return;
    setBusy(-1);
    try {
      await templatesApi.clone(workspaceId, templateId, newName);
      onRollback?.();
      onOpenChange(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Template Version History</DialogTitle>
          <DialogDescription>
            Each save creates a new version. Roll back to a previous version, or clone this template.
          </DialogDescription>
        </DialogHeader>

        {loading ? (
          <LoadingState message="Loading versions..." />
        ) : error ? (
          <ErrorState message={error} />
        ) : versions.length === 0 ? (
          <p className="text-sm text-muted-foreground">No versions found.</p>
        ) : (
          <div className="space-y-2 max-h-96 overflow-y-auto">
            {versions.map((v) => (
              <div key={v.id} className="rounded-md border p-3 flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <Badge variant={v.id === templateId ? 'success' : 'muted'} className="text-[10px]">
                      v{v.version}
                    </Badge>
                    {v.id === templateId && (
                      <span className="text-xs text-muted-foreground">(current)</span>
                    )}
                  </div>
                  <div className="text-sm font-medium mt-1">{v.name}</div>
                  <div className="text-xs text-muted-foreground">
                    {format(new Date(v.updatedAt), 'MMM d, yyyy HH:mm')}
                  </div>
                </div>
                <div className="flex gap-1">
                  {v.id !== templateId && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void rollback(v.version)}
                      disabled={busy === v.version}
                    >
                      <RotateCcw className="mr-1 h-3 w-3" /> Rollback
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="flex justify-end pt-2 border-t">
          <Button variant="outline" onClick={() => void cloneTemplate()} disabled={busy === -1}>
            <Copy className="mr-1 h-3 w-3" /> Clone template
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
