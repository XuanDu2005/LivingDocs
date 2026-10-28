import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { GitMerge, ShieldCheck, Zap } from 'lucide-react';
import { Badge } from './ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { ErrorState, LoadingState } from './ui/states';
import {
  governanceApi, GovernancePolicy, MergeGateVerdict,
} from '../services/governance';
import { describeError } from '../services/auth';

/**
 * Shows the governance policy the runtime actually enforces.
 *
 * <p>This is read-only on purpose. The settings screen lets a manager
 * change the knobs; this card shows the resolved values — including the
 * per-doc-type overrides that the settings form does not surface — so
 * nobody has to guess why a version was published or held for review.
 */
export default function GovernancePanel({ workspaceId }: { workspaceId: string }) {
  const { t } = useTranslation();
  const [policy, setPolicy] = useState<GovernancePolicy | null>(null);
  const [mergeGate, setMergeGate] = useState<MergeGateVerdict | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [p, g] = await Promise.all([
        governanceApi.policy(workspaceId),
        governanceApi.mergeGate(workspaceId).catch(() => null),
      ]);
      setPolicy(p);
      setMergeGate(g);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => { void load(); }, [load]);

  if (loading) return <LoadingState message={t('governance.loading')} />;
  if (error) return <ErrorState message={error} />;
  if (!policy) return null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldCheck className="h-4 w-4" /> {t('governance.title')}
        </CardTitle>
        <CardDescription>{t('governance.subtitle')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid gap-3 sm:grid-cols-2">
          <Row
            icon={<Zap className="h-4 w-4" />}
            label={t('governance.requireManagerApproval')}
            value={policy.requireManagerApproval
              ? t('governance.yes')
              : t('governance.no')}
          />
          <Row
            label={t('governance.confidenceThreshold')}
            value={`${Math.round(policy.confidenceThreshold * 100)}%`}
          />
          <Row
            label={t('governance.mergePolicy')}
            value={<Badge variant={policy.mergePolicy === 'BLOCK' ? 'destructive' : 'warning'}>
              {policy.mergePolicy}
            </Badge>}
          />
          <Row
            label={t('governance.driftSeverityThreshold')}
            value={policy.driftSeverityThreshold}
          />
        </dl>

        {mergeGate && (
          <div className="flex items-start gap-2 rounded-md border bg-muted/30 p-3 text-sm">
            <GitMerge className="mt-0.5 h-4 w-4 shrink-0" />
            <div className="space-y-1">
              <div className="font-medium">
                {mergeGate.blocked
                  ? t('governance.mergeGateBlocked')
                  : t('governance.mergeGateOpen')}
              </div>
              <p className="text-xs text-muted-foreground">{mergeGate.reason}</p>
              {mergeGate.blockingAlertCount > 0 && (
                <p className="text-xs text-muted-foreground">
                  {mergeGate.blockingAlertCount} {t('governance.blockingAlerts')}
                </p>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Row({
  icon, label, value,
}: {
  icon?: React.ReactNode;
  label: string;
  value: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border px-3 py-2">
      <dt className="flex items-center gap-1.5 text-sm text-muted-foreground">
        {icon}
        {label}
      </dt>
      <dd className="text-sm font-medium">{value}</dd>
    </div>
  );
}
