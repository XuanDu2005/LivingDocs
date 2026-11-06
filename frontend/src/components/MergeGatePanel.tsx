import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  Check,
  Copy,
  Download,
  GitMerge,
  KeyRound,
  Loader2,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from './ui/card';
import { ErrorState, LoadingState } from './ui/states';
import { describeError } from '../services/auth';
import { cicdApi } from '../services/cicd';
import {
  governanceApi,
  GovernancePolicy,
  MergeGateVerdict,
} from '../services/governance';
import { githubApi, Repository } from '../services/github';

/**
 * The merge gate, as the person who configures it sees it.
 *
 * <p>Three things live here:
 *
 * <ul>
 *   <li><b>The policy</b> — the effective merge policy of the
 *       workspace, and whether the gate would currently block.</li>
 *   <li><b>The credential</b> — the CI token a workflow presents. It is
 *       shown exactly once, because the platform stores only its
 *       hash.</li>
 *   <li><b>The workflow</b> — the generated GitHub Actions file, which
 *       asks LivingDocs for the verdict instead of deciding on its
 *       own.</li>
 * </ul>
 */
interface Props {
  workspaceId: string;
  /** Whether the caller may issue / revoke the CI token. */
  canManage: boolean;
  /** Whether the caller may read the governance policy at all. */
  canView: boolean;
}

export default function MergeGatePanel({ workspaceId, canManage, canView }: Props) {
  const { t } = useTranslation();
  const [policy, setPolicy] = useState<GovernancePolicy | null>(null);
  const [verdict, setVerdict] = useState<MergeGateVerdict | null>(null);
  const [hasToken, setHasToken] = useState<boolean>(false);
  const [repos, setRepos] = useState<Repository[]>([]);
  const [selectedRepo, setSelectedRepo] = useState<string>('');
  const [workflow, setWorkflow] = useState<string>('');
  const [issuedToken, setIssuedToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [p, g, token, repositoryList] = await Promise.all([
        governanceApi.policy(workspaceId),
        governanceApi.mergeGate(workspaceId).catch(() => null),
        cicdApi.tokenStatus(workspaceId).catch(() => null),
        githubApi.listRepositories(workspaceId).catch(() => []),
      ]);
      setPolicy(p);
      setVerdict(g);
      setHasToken(token?.hasToken ?? false);
      setRepos(repositoryList);
      if (repositoryList.length > 0 && !selectedRepo) {
        setSelectedRepo(repositoryList[0].id);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, selectedRepo]);

  useEffect(() => { void load(); }, [load]);

  async function rotateToken() {
    setBusy('token');
    setError(null);
    setInfo(null);
    try {
      const issued = await cicdApi.rotateToken(workspaceId);
      setIssuedToken(issued.token);
      setHasToken(true);
      setInfo(t('mergeGate.tokenIssued'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function revokeToken() {
    setBusy('token');
    setError(null);
    setInfo(null);
    try {
      await cicdApi.revokeToken(workspaceId);
      setIssuedToken(null);
      setHasToken(false);
      setInfo(t('mergeGate.tokenRevoked'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function downloadWorkflow() {
    if (!selectedRepo) return;
    setBusy('workflow');
    setError(null);
    try {
      const yaml = await cicdApi.githubActionsWorkflow(workspaceId, selectedRepo);
      setWorkflow(yaml);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  if (!canView) return null;
  if (loading) return <LoadingState message={t('mergeGate.loading')} />;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <GitMerge className="h-4 w-4" /> {t('mergeGate.title')}
        </CardTitle>
        <CardDescription>{t('mergeGate.subtitle')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {info && (
          <div className="flex items-center gap-2 rounded border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700">
            <Check className="h-4 w-4" /> {info}
          </div>
        )}
        {error && <ErrorState message={error} />}

        {/* ---- the effective policy ---- */}
        {policy && (
          <div className="space-y-2 rounded-md border p-3">
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-muted-foreground">
                {t('mergeGate.effectivePolicy')}
              </span>
              <Badge variant={policy.mergePolicy === 'BLOCK' ? 'destructive' : 'warning'}>
                {policy.mergePolicy}
              </Badge>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-sm text-muted-foreground">
                {t('mergeGate.severityThreshold')}
              </span>
              <span className="text-sm font-medium">
                {policy.driftSeverityThreshold}
              </span>
            </div>
            {verdict && (
              <div className="flex items-start gap-2 rounded border bg-muted/30 p-2 text-sm">
                {verdict.blocked ? (
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" />
                ) : (
                  <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-green-600" />
                )}
                <div className="space-y-1">
                  <div className="font-medium">
                    {verdict.blocked
                      ? t('governance.mergeGateBlocked')
                      : t('governance.mergeGateOpen')}
                  </div>
                  <p className="text-xs text-muted-foreground">{verdict.reason}</p>
                  {verdict.blockingAlertCount > 0 && (
                    <p className="text-xs text-muted-foreground">
                      {verdict.blockingAlertCount} {t('governance.blockingAlerts')}
                    </p>
                  )}
                </div>
              </div>
            )}
          </div>
        )}

        {/* ---- the CI credential ---- */}
        <div className="space-y-2 rounded-md border p-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="flex items-center gap-1.5 text-sm font-medium">
                <KeyRound className="h-4 w-4" /> {t('mergeGate.ciToken')}
              </div>
              <p className="text-xs text-muted-foreground">
                {t('mergeGate.ciTokenHint')}
              </p>
            </div>
            {canManage && (
              <div className="flex gap-2">
                {hasToken ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => void rotateToken()}
                    disabled={busy === 'token'}
                  >
                    {busy === 'token'
                      ? <Loader2 className="h-4 w-4 animate-spin" />
                      : t('mergeGate.rotateToken')}
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    onClick={() => void rotateToken()}
                    disabled={busy === 'token'}
                  >
                    {busy === 'token'
                      ? <Loader2 className="h-4 w-4 animate-spin" />
                      : t('mergeGate.issueToken')}
                  </Button>
                )}
                {hasToken && (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => void revokeToken()}
                    disabled={busy === 'token'}
                    title={t('mergeGate.revokeToken')}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                )}
              </div>
            )}
          </div>

          {!hasToken && (
            <p className="text-xs text-muted-foreground">{t('mergeGate.noToken')}</p>
          )}

          {issuedToken && (
            <div className="space-y-1 rounded border border-amber-500/40 bg-amber-500/10 p-2">
              <p className="text-xs font-medium">{t('mergeGate.tokenShownOnce')}</p>
              <div className="flex items-center gap-2">
                <code className="min-w-0 flex-1 break-all rounded bg-card px-2 py-1 text-xs">
                  {issuedToken}
                </code>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    void navigator.clipboard?.writeText(issuedToken);
                  }}
                  title={t('mergeGate.copyToken')}
                >
                  <Copy className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
        </div>

        {/* ---- the generated workflow ---- */}
        <div className="space-y-2 rounded-md border p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <div className="text-sm font-medium">{t('mergeGate.workflowTitle')}</div>
              <p className="text-xs text-muted-foreground">
                {t('mergeGate.workflowHint')}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <select
                className="rounded-md border bg-background px-2 py-1 text-sm"
                value={selectedRepo}
                onChange={(e) => {
                  setSelectedRepo(e.target.value);
                  setWorkflow('');
                }}
              >
                {repos.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.fullName}
                  </option>
                ))}
              </select>
              <Button
                size="sm"
                variant="outline"
                onClick={() => void downloadWorkflow()}
                disabled={busy === 'workflow' || !selectedRepo}
              >
                {busy === 'workflow'
                  ? <Loader2 className="h-4 w-4 animate-spin" />
                  : <Download className="h-4 w-4" />}
                {t('mergeGate.showWorkflow')}
              </Button>
            </div>
          </div>

          {workflow && (
            <pre className="max-h-96 overflow-auto rounded bg-muted/40 p-3 text-xs">
              {workflow}
            </pre>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
