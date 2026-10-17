import { useEffect, useState } from 'react';
import { Diff2HtmlUI } from 'diff2html/lib/ui/js/diff2html-ui-slim';
import { X, GitPullRequest, AlertTriangle, CheckCircle2, Info, ShieldCheck } from 'lucide-react';
import { docSyncApi, docSyncQueueApi, DocSyncPreview, DriftFinding } from '../services/docSync';
import { listMembers } from '../services/workspaces';
import { authStorage } from '../services/auth';

interface DiffPreviewProps {
  workspaceId: string;
  repositoryId: string;
  filePath: string;
  branch?: string;
  onClose: () => void;
  onApplied: (prUrl: string) => void;
}

export function DocSyncPreviewModal({
  workspaceId,
  repositoryId,
  filePath,
  branch,
  onClose,
  onApplied,
}: DiffPreviewProps) {
  const [preview, setPreview] = useState<DocSyncPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const [queuing, setQueuing] = useState(false);
  const [queued, setQueued] = useState<{ id: string; message?: string } | null>(null);
  const [isManager, setIsManager] = useState<boolean>(false);

  // Fetch preview on mount
  useEffect(() => {
    docSyncApi.preview(workspaceId, repositoryId, filePath, branch)
      .then(setPreview)
      .catch((err) => setError(err.response?.data?.message || 'Failed to generate preview'))
      .finally(() => setLoading(false));
  }, [workspaceId, repositoryId, filePath, branch]);

  // Check current user's workspace role.
  // MANAGER can push PR directly, so we hide the "Queue for review" button
  // for managers to avoid the extra (and confusing) step.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const user = authStorage.getUser();
        if (!user) return;
        const members = await listMembers(workspaceId);
        if (cancelled) return;
        const me = members.find((m) => m.userId === user.id);
        setIsManager(me?.role === 'MANAGER');
      } catch {
        // If we can't determine role, default to showing the queue button
        // (safer than silently allowing non-managers to push PRs).
        if (!cancelled) setIsManager(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  const handleApply = async () => {
    if (!preview) return;

    // Guard: if no entities found, there's nothing to propose
    if (preview.noEntitiesFound) {
      setError('No code entities found — cannot generate documentation updates for this file.');
      return;
    }

    // Guard: if proposed content is identical to current, there's nothing to commit.
    if (preview.proposedContent === preview.currentContent) {
      setError('No changes detected — documentation already matches the code.');
      return;
    }

    setApplying(true);
    try {
      const result = await docSyncApi.apply(
        workspaceId,
        repositoryId,
        filePath,
        preview.proposedContent!,
        branch,
      );
      if (result.success) {
        onApplied(result.prUrl);
      } else {
        setError(result.message || 'Failed to apply changes');
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to apply changes');
    } finally {
      setApplying(false);
    }
  };

  const handleQueue = async () => {
    if (!preview) return;
    if (preview.noEntitiesFound) {
      setError('No code entities found — cannot queue a doc update for review.');
      return;
    }
    if (preview.proposedContent === preview.currentContent) {
      setError('No changes detected — documentation already matches the code.');
      return;
    }
    setQueuing(true);
    setError(null);
    try {
      const result = await docSyncQueueApi.queue(workspaceId, repositoryId, filePath, branch);
      if (result?.hasChanges) {
        setQueued({ id: result.id, message: result.message });
      } else {
        setError(result?.message || 'No changes to queue — documentation already matches the code.');
      }
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to queue doc update for review');
    } finally {
      setQueuing(false);
    }
  };

  // Check if we can create PR
  const canCreatePR = preview && !preview.noEntitiesFound && preview.proposedContent !== preview.currentContent;

  // Check if file has no entities found
  const hasNoEntities = preview?.noEntitiesFound;

  // Render diff using diff2html
  const renderDiff = (diff: string | null) => {
    if (!diff) return null;
    
    const diffHtml = document.createElement('div');
    const diff2htmlUi = new Diff2HtmlUI(diffHtml, diff, {
      drawFileList: true,
      matching: 'lines',
      outputFormat: 'side-by-side',
      highlight: true,
      fileContentToggle: true,
    });
    diff2htmlUi.draw();
    diff2htmlUi.highlightCode();
    
    return <div dangerouslySetInnerHTML={{ __html: diffHtml.innerHTML }} />;
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-white rounded-lg shadow-xl w-[90vw] max-w-6xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b">
          <div>
            <h2 className="text-xl font-semibold">Documentation Sync Preview</h2>
            <p className="text-sm text-gray-500">{filePath}</p>
          </div>
          <button
            onClick={onClose}
            className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-auto p-6">
          {loading && (
            <div className="flex items-center justify-center h-64">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
              <span className="ml-3 text-gray-600">Generating preview...</span>
            </div>
          )}

          {error && (
            <div className="flex items-center justify-center h-64">
              <div className="text-center">
                <AlertTriangle className="w-12 h-12 mx-auto mb-3 text-red-500" />
                <p className="text-red-600">{error}</p>
              </div>
            </div>
          )}

          {preview && !loading && (
            <div className="space-y-6">
              {/* No Entities Found State */}
              {hasNoEntities && (
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-6">
                  <div className="flex items-start gap-4">
                    <div className="p-3 bg-blue-100 rounded-full">
                      <Info className="w-6 h-6 text-blue-600" />
                    </div>
                    <div className="flex-1">
                      <h3 className="font-semibold text-blue-900 text-lg">
                        No code entities found
                      </h3>
                      <p className="mt-2 text-blue-800">
                        This documentation file does not reference any code entities in the repository.
                        This may be because:
                      </p>
                      <ul className="mt-3 space-y-2 text-blue-700">
                        <li className="flex items-start gap-2">
                          <span className="mt-1.5 w-2 h-2 bg-blue-400 rounded-full flex-shrink-0" />
                          <span>The file is unrelated to code (e.g., onboarding guide, deployment instructions, team processes)</span>
                        </li>
                        <li className="flex items-start gap-2">
                          <span className="mt-1.5 w-2 h-2 bg-blue-400 rounded-full flex-shrink-0" />
                          <span>The file is already well-documented and references code entities that match their current implementation</span>
                        </li>
                        <li className="flex items-start gap-2">
                          <span className="mt-1.5 w-2 h-2 bg-blue-400 rounded-full flex-shrink-0" />
                          <span>Code entity extraction may have missed some definitions</span>
                        </li>
                      </ul>
                      <p className="mt-4 text-sm text-blue-600">
                        <strong>Tip:</strong> If this file should reference code, consider updating the code entity extraction rules
                        or adding explicit references to class/method names in the documentation.
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {/* Findings Summary */}
              {preview.findings.length > 0 && !hasNoEntities && (
                <div className="bg-amber-50 border border-amber-200 rounded-lg p-4">
                  <h3 className="font-semibold text-amber-800 flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4" />
                    Documentation Issues Found
                  </h3>
                  <ul className="mt-2 space-y-2">
                    {preview.findings.map((finding, i) => (
                      <FindingItem key={i} finding={finding} />
                    ))}
                  </ul>
                </div>
              )}

              {/* Diff View */}
              {!hasNoEntities && (
                <div className="border rounded-lg overflow-hidden">
                  <div className="bg-gray-50 px-4 py-2 border-b font-medium text-sm">
                    Changes Preview
                  </div>
                  <div className="p-4 overflow-auto max-h-[50vh] diff2html-custom-styles">
                    {preview.diff ? renderDiff(preview.diff) : (
                      <p className="text-gray-500">No changes detected</p>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t bg-gray-50">
          <button
            onClick={onClose}
            className="px-4 py-2 text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
            disabled={applying}
          >
            Cancel
          </button>

          {/* No Entities Found State */}
          {hasNoEntities && (
            <div className="flex items-center gap-2 px-4 py-2 text-blue-700 bg-blue-50 border border-blue-200 rounded-lg">
              <Info className="w-4 h-4" />
              <span className="text-sm font-medium">
                No entities found — no update needed
              </span>
            </div>
          )}

          {/* Already up to date */}
          {!hasNoEntities && preview && preview.proposedContent === preview.currentContent && (
            <div className="flex items-center gap-2 px-4 py-2 text-amber-700 bg-amber-50 border border-amber-200 rounded-lg">
              <CheckCircle2 className="w-4 h-4" />
              <span className="text-sm font-medium">
                Already up to date — no changes to commit
              </span>
            </div>
          )}

          {/* Queue for Review + Create PR buttons */}
          {canCreatePR && (
            <div className="flex items-center gap-2">
              {queued ? (
                <div className="flex items-center gap-2 px-4 py-2 bg-green-50 text-green-800 rounded-lg border border-green-200">
                  <CheckCircle2 className="w-4 h-4" />
                  <span>Queued for review (id: {queued.id.slice(0, 8)}…)</span>
                </div>
              ) : !isManager ? (
                // Members / non-managers: queue for review first
                <button
                  onClick={handleQueue}
                  disabled={!preview || queuing || applying}
                  className="flex items-center gap-2 px-4 py-2 bg-amber-600 text-white rounded-lg hover:bg-amber-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  title="Queue this update for Staff/Manager review. A PENDING_REVIEW DocUpdateRequest will be created; a Manager can then push the PR to Git."
                >
                  {queuing ? (
                    <>
                      <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white" />
                      Queuing…
                    </>
                  ) : (
                    <>
                      <ShieldCheck className="w-4 h-4" />
                      Queue for review
                    </>
                  )}
                </button>
              ) : null}
              <button
                onClick={handleApply}
                disabled={!preview || applying || queuing}
                className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                title={isManager
                  ? "Push this update to GitHub as a Pull Request."
                  : "Push this update to GitHub as a Pull Request (Manager only — request review if you don't have access)."}
              >
                {applying ? (
                  <>
                    <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white" />
                    Creating PR...
                  </>
                ) : (
                  <>
                    <GitPullRequest className="w-4 h-4" />
                    Create Pull Request
                  </>
                )}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function FindingItem({ finding }: { finding: DriftFinding }) {
  const severityColors = {
    low: 'text-blue-600 bg-blue-50',
    medium: 'text-amber-600 bg-amber-50',
    high: 'text-red-600 bg-red-50',
  };
  
  const severity = (finding.severity || 'medium').toLowerCase() as keyof typeof severityColors;
  const colorClass = severityColors[severity] || severityColors.medium;

  return (
    <li className="text-sm">
      <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${colorClass}`}>
        {finding.severity?.toUpperCase() || 'MEDIUM'}
      </span>
      <span className="ml-2 font-medium">{finding.title}</span>
      <p className="text-gray-600 mt-1">{finding.description}</p>
      {finding.suggestion && (
        <p className="text-gray-500 mt-1 italic">Suggestion: {finding.suggestion}</p>
      )}
    </li>
  );
}
