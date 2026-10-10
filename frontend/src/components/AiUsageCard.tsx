import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Button } from './ui/button';
import { Loader2, Save, Gauge } from 'lucide-react';
import { describeError } from '../services/auth';
import { AiUsageSummary, aiUsageApi, UpdateLimitsPayload } from '../services/aiUsage';

export function AiUsageCard() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const [summary, setSummary] = useState<AiUsageSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [dailyLimit, setDailyLimit] = useState<string>('');
  const [monthlyLimit, setMonthlyLimit] = useState<string>('');
  const [rateLimit, setRateLimit] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const s = await aiUsageApi.getSummary(workspaceId);
      setSummary(s);
      setDailyLimit(s.dailyLimit?.toString() ?? '');
      setMonthlyLimit(s.monthlyLimit?.toString() ?? '');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [workspaceId]);

  async function save() {
    if (!workspaceId) return;
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const payload: UpdateLimitsPayload = {
        dailyTokenLimit: dailyLimit ? Number(dailyLimit) : null,
        monthlyTokenLimit: monthlyLimit ? Number(monthlyLimit) : null,
        rateLimitPerMinute: rateLimit ? Number(rateLimit) : null,
      };
      const updated = await aiUsageApi.updateLimits(workspaceId, payload);
      setSummary(updated);
      setInfo('Limits saved.');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  const dailyPct = summary && summary.dailyLimit
    ? Math.min(100, (summary.todayTokens / summary.dailyLimit) * 100)
    : 0;
  const monthlyPct = summary && summary.monthlyLimit
    ? Math.min(100, (summary.monthTokens / summary.monthlyLimit) * 100)
    : 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-2">
          <Gauge className="h-4 w-4" /> Usage & Limits
        </CardTitle>
        <CardDescription>
          Track AI token usage and configure daily/monthly budget limits.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {loading || !summary ? (
          <p className="text-sm text-muted-foreground">Loading usage...</p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-md border p-3">
                <div className="text-xs text-muted-foreground">Today</div>
                <div className="text-lg font-bold">{summary.todayTokens.toLocaleString()}</div>
                {summary.dailyLimit && (
                  <div className="mt-2">
                    <div className="h-1.5 w-full rounded-full bg-muted">
                      <div
                        className={`h-full rounded-full ${dailyPct > 80 ? 'bg-destructive' : 'bg-primary'}`}
                        style={{ width: `${dailyPct}%` }}
                      />
                    </div>
                    <div className="text-xs text-muted-foreground mt-1">
                      of {summary.dailyLimit.toLocaleString()} ({dailyPct.toFixed(0)}%)
                    </div>
                  </div>
                )}
              </div>
              <div className="rounded-md border p-3">
                <div className="text-xs text-muted-foreground">This month</div>
                <div className="text-lg font-bold">{summary.monthTokens.toLocaleString()}</div>
                {summary.monthlyLimit && (
                  <div className="mt-2">
                    <div className="h-1.5 w-full rounded-full bg-muted">
                      <div
                        className={`h-full rounded-full ${monthlyPct > 80 ? 'bg-destructive' : 'bg-primary'}`}
                        style={{ width: `${monthlyPct}%` }}
                      />
                    </div>
                    <div className="text-xs text-muted-foreground mt-1">
                      of {summary.monthlyLimit.toLocaleString()} ({monthlyPct.toFixed(0)}%)
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div className="space-y-1.5">
                <Label htmlFor="daily-limit">Daily token limit</Label>
                <Input
                  id="daily-limit"
                  type="number"
                  min={0}
                  value={dailyLimit}
                  onChange={(e) => setDailyLimit(e.target.value)}
                  placeholder="Unlimited"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="monthly-limit">Monthly token limit</Label>
                <Input
                  id="monthly-limit"
                  type="number"
                  min={0}
                  value={monthlyLimit}
                  onChange={(e) => setMonthlyLimit(e.target.value)}
                  placeholder="Unlimited"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="rate-limit">Requests / min</Label>
                <Input
                  id="rate-limit"
                  type="number"
                  min={0}
                  value={rateLimit}
                  onChange={(e) => setRateLimit(e.target.value)}
                  placeholder="Unlimited"
                />
              </div>
            </div>

            {error && (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                {error}
              </div>
            )}
            {info && (
              <div className="rounded-md border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700">
                {info}
              </div>
            )}

            <Button onClick={() => void save()} disabled={saving}>
              {saving ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Save className="mr-1 h-4 w-4" />}
              Save limits
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  );
}
