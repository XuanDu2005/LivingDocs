import { useCallback, useEffect, useMemo, useState } from 'react';
import { TFunction } from 'i18next';
import { UserCheck, UserMinus } from 'lucide-react';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../ui/select';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { LoadingState, ErrorState, EmptyState } from '../ui/states';
import {
  delegationsApi, DelegationTier, ReviewDelegation,
} from '../../services/drift';
import { listMembers, WorkspaceMember } from '../../services/workspaces';
import { describeError } from '../../services/auth';

interface DelegationPanelProps {
  workspaceId: string;
  /** True when the caller may delegate — the panel is hidden otherwise. */
  canDelegate: boolean;
  t: TFunction;
}

/**
 * The delegation surface of the review queue.
 *
 * <p>A Manager can hand the review (STAFF tier) or approval (MANAGER
 * tier) work of their workspace to somebody else. The panel lists the
 * delegations of the workspace — in force and revoked — and lets a
 * Manager create or end one.
 *
 * <p>The delegate has to be a workspace member who holds the permission
 * of the tier. The backend enforces that, so the UI does not try to
 * re-implement it: it offers every member and lets the API refuse the
 * ones that cannot act. A member list is workspace-scoped, so no
 * member outside the workspace can even be named here.
 */
export default function DelegationPanel({
  workspaceId, canDelegate, t,
}: DelegationPanelProps) {
  const [delegations, setDelegations] = useState<ReviewDelegation[]>([]);
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [toUserId, setToUserId] = useState<string>('');
  const [tier, setTier] = useState<DelegationTier>('MANAGER');
  const [reason, setReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, memberList] = await Promise.all([
        delegationsApi.list(workspaceId),
        listMembers(workspaceId).catch(() => [] as WorkspaceMember[]),
      ]);
      setDelegations(list);
      setMembers(memberList);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => { void load(); }, [load]);

  /**
   * The members who may be picked as a delegate.
   *
   * <p>The caller is excluded: delegating your work to yourself is
   * meaningless, and the backend refuses it anyway.
   */
  const candidates = useMemo(
    () => members.filter((m) => m.userId !== toUserId),
    [members, toUserId],
  );

  async function submit() {
    if (!toUserId) return;
    setBusy(true);
    setError(null);
    try {
      await delegationsApi.create(workspaceId, {
        toUserId,
        tier,
        reason: reason.trim() || undefined,
      });
      setToUserId('');
      setReason('');
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    setBusy(true);
    setError(null);
    try {
      await delegationsApi.revoke(workspaceId, id);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  const active = delegations.filter((d) => d.active);
  const past = delegations.filter((d) => !d.active);

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">
          <UserCheck className="mr-2 inline h-4 w-4" />
          {t('reviews.delegation.title', 'Review delegation')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && <ErrorState message={error} />}
        {loading && <LoadingState message={t('reviews.delegation.loading', 'Loading delegations')} />}

        {!loading && canDelegate && (
          <div className="flex flex-wrap items-end gap-3 rounded-md border p-3">
            <div className="min-w-[12rem] flex-1">
              <Label className="text-xs">
                {t('reviews.delegation.delegate', 'Delegate')}
              </Label>
              <Select value={toUserId} onValueChange={setToUserId}>
                <SelectTrigger className="h-9">
                  <SelectValue
                    placeholder={t('reviews.delegation.pickDelegate', 'Pick a member')}
                  />
                </SelectTrigger>
                <SelectContent>
                  {candidates.map((m) => (
                    <SelectItem key={m.userId} value={m.userId}>
                      {m.displayName || m.userEmail || m.userId}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="min-w-[8rem]">
              <Label className="text-xs">{t('reviews.delegation.tier', 'Tier')}</Label>
              <Select
                value={tier}
                onValueChange={(v) => setTier(v as DelegationTier)}
              >
                <SelectTrigger className="h-9">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="MANAGER">
                    {t('reviews.delegation.tierManager', 'Manager (approval)')}
                  </SelectItem>
                  <SelectItem value="STAFF">
                    {t('reviews.delegation.tierStaff', 'Staff (review)')}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="min-w-[12rem] flex-1">
              <Label className="text-xs">
                {t('reviews.delegation.reason', 'Reason')}
              </Label>
              <Input
                className="h-9"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={t('reviews.delegation.reasonHint', 'On leave, second pair of eyes, …')}
              />
            </div>

            <Button size="sm" disabled={busy || !toUserId} onClick={() => void submit()}>
              {t('reviews.delegation.create', 'Delegate')}
            </Button>
          </div>
        )}

        {!loading && delegations.length === 0 && (
          <EmptyState
            title={t('reviews.delegation.empty', 'No delegation yet')}
            description={t(
              'reviews.delegation.emptyDesc',
              'Hand the review or approval work of this workspace to another member.',
            )}
          />
        )}

        {active.length > 0 && (
          <DelegationTable
            rows={active}
            canDelegate={canDelegate}
            busy={busy}
            onRevoke={(id) => void revoke(id)}
            t={t}
          />
        )}

        {past.length > 0 && (
          <details>
            <summary className="cursor-pointer text-xs text-muted-foreground">
              {t('reviews.delegation.history', 'Ended delegations')} ({past.length})
            </summary>
            <div className="mt-2">
              <DelegationTable
                rows={past}
                canDelegate={false}
                busy={busy}
                onRevoke={() => {}}
                t={t}
              />
            </div>
          </details>
        )}
      </CardContent>
    </Card>
  );
}

interface DelegationTableProps {
  rows: ReviewDelegation[];
  canDelegate: boolean;
  busy: boolean;
  onRevoke: (id: string) => void;
  t: TFunction;
}

function DelegationTable({ rows, canDelegate, busy, onRevoke, t }: DelegationTableProps) {
  return (
    <ul className="space-y-2">
      {rows.map((d) => (
        <li
          key={d.id}
          className="flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
        >
          <div className="min-w-0">
            <p className="text-sm">
              <span className="font-medium">
                {d.fromUserName || d.fromUserId}
              </span>
              {' → '}
              <span className="font-medium">
                {d.toUserName || d.toUserId}
              </span>
            </p>
            <p className="text-xs text-muted-foreground">
              <Badge variant="muted" className="mr-2">{d.tier}</Badge>
              {d.reason && <span className="mr-2">{d.reason}</span>}
              {new Date(d.createdAt).toLocaleString()}
              {!d.active && d.revokedAt && (
                <span className="ml-2">
                  {t('reviews.delegation.ended', 'ended')} {new Date(d.revokedAt).toLocaleString()}
                </span>
              )}
            </p>
          </div>
          {canDelegate && d.active && (
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => onRevoke(d.id)}
            >
              <UserMinus className="mr-1 h-4 w-4" />
              {t('reviews.delegation.revoke', 'End')}
            </Button>
          )}
        </li>
      ))}
    </ul>
  );
}
