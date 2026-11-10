import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Loader2, RotateCcw, ShieldCheck } from 'lucide-react';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from './ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/select';
import { ErrorState, LoadingState } from './ui/states';
import { describeError } from '../services/auth';
import {
  canManageDocTypePolicies,
  DOC_TYPE_LABELS,
  DocTypeWorkflow,
  WorkspaceDocTypePolicy,
  workspaceDocTypePoliciesApi,
} from '../services/workspaceDocTypePolicies';

/**
 * Per-workspace table of the doc-type workflow policies.
 *
 * <p>Each row shows the *effective* workflow — the one the runtime
 * enforces — and where it comes from:
 *
 * <ul>
 *   <li>{@code WORKSPACE} — set here, in this workspace.</li>
 *   <li>{@code PLATFORM} — inherited from the platform's per-doc-type
 *       policy.</li>
 *   <li>{@code DEFAULT} — no row anywhere; the workspace's
 *       {@code requireManagerApproval} flag decides.</li>
 * </ul>
 *
 * <p>Picking a workflow writes a workspace override. Clearing it removes
 * the override again, so the doc type falls back to the platform policy.
 */
interface Props {
  workspaceId: string;
  /**
   * Whether the current user holds the `GOVERNANCE_MANAGE` capability.
   *
   * <p>The capability alone is not enough: the API also requires a
   * workspace role that owns the documentation governance — the workspace
   * `MANAGER` or the workspace `DOCUMENTATION_OWNER`. The panel
   * resolves both before it offers the controls. Otherwise a
   * TECHNICAL_LEAD — who holds the capability but is only a workspace
   * member — would see selectors that the API refuses.
   */
  hasGovernanceManage: boolean;
}

export default function WorkspaceDocTypePolicyPanel({
  workspaceId,
  hasGovernanceManage,
}: Props) {
  const { t } = useTranslation();
  const [rows, setRows] = useState<WorkspaceDocTypePolicy[]>([]);
  const [canManage, setCanManage] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  /**
   * Reload the policies.
   *
   * <p>The first load shows the loading state; a refresh after a change
   * does not, because blanking the whole panel to re-render one row is
   * a worse experience than a brief spinner next to that row.
   */
  const load = useCallback(
    async (showSpinner = true) => {
      if (showSpinner) setLoading(true);
      setError(null);
      try {
        const [policies, manageable] = await Promise.all([
          workspaceDocTypePoliciesApi.list(workspaceId),
          canManageDocTypePolicies(workspaceId, hasGovernanceManage),
        ]);
        setRows(policies);
        setCanManage(manageable);
      } catch (err) {
        setError(describeError(err));
      } finally {
        if (showSpinner) setLoading(false);
      }
    },
    [workspaceId, hasGovernanceManage],
  );

  useEffect(() => {
    void load();
  }, [load]);

  async function change(row: WorkspaceDocTypePolicy, workflow: DocTypeWorkflow) {
    if (workflow === row.workspaceWorkflow) return;
    setBusy(row.docType);
    setError(null);
    setInfo(null);
    try {
      const updated = await workspaceDocTypePoliciesApi.upsert(workspaceId, row.docType, {
        workflow,
      });
      setRows((prev) => prev.map((r) => (r.docType === updated.docType ? updated : r)));
      setInfo(t('workspaceDocTypePolicies.saved', { docType: labelOf(row.docType) }));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function reset(row: WorkspaceDocTypePolicy) {
    setBusy(row.docType);
    setError(null);
    setInfo(null);
    try {
      await workspaceDocTypePoliciesApi.remove(workspaceId, row.docType);
      await load(false);
      setInfo(
        t('workspaceDocTypePolicies.reset', { docType: labelOf(row.docType) }),
      );
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  if (loading) return <LoadingState message={t('workspaceDocTypePolicies.loading')} />;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldCheck className="h-4 w-4" /> {t('workspaceDocTypePolicies.title')}
        </CardTitle>
        <CardDescription>{t('workspaceDocTypePolicies.subtitle')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {info && (
          <div className="flex items-center gap-2 rounded border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700">
            <Check className="h-4 w-4" /> {info}
          </div>
        )}
        {error && <ErrorState message={error} />}

        <ul className="divide-y">
          {rows.map((row) => (
            <li key={row.docType} className="flex flex-wrap items-center gap-3 py-3">
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">{labelOf(row.docType)}</div>
                <code className="text-xs text-muted-foreground">{row.docType}</code>
              </div>

              <Badge
                variant={row.scope === 'WORKSPACE' ? 'info' : 'muted'}
                className="text-[10px]"
              >
                {t(`workspaceDocTypePolicies.scope.${row.scope}`)}
              </Badge>

              <Badge
                variant={row.workflow === 'AUTO_APPLY' ? 'success' : 'warning'}
                className="text-[10px]"
              >
                {t(`workspaceDocTypePolicies.workflow.${row.workflow}`)}
              </Badge>

              {canManage && (
                <div className="flex items-center gap-2">
                  <Select
                    value={row.workspaceWorkflow ?? '__inherit__'}
                    onValueChange={(v) => {
                      if (v === '__inherit__') {
                        void reset(row);
                        return;
                      }
                      void change(row, v as DocTypeWorkflow);
                    }}
                    disabled={busy === row.docType}
                  >
                    <SelectTrigger className="h-8 w-52">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__inherit__">
                        {t('workspaceDocTypePolicies.inheritOption')}
                      </SelectItem>
                      <SelectItem value="AUTO_APPLY">
                        {t('workspaceDocTypePolicies.workflow.AUTO_APPLY')}
                      </SelectItem>
                      <SelectItem value="MANAGER_REVIEW">
                        {t('workspaceDocTypePolicies.workflow.MANAGER_REVIEW')}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                  {busy === row.docType ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : row.scope === 'WORKSPACE' ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void reset(row)}
                      title={t('workspaceDocTypePolicies.resetHint')}
                    >
                      <RotateCcw className="h-3 w-3" />
                    </Button>
                  ) : null}
                </div>
              )}
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

function labelOf(docType: string): string {
  return DOC_TYPE_LABELS[docType] ?? docType;
}
