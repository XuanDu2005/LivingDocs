import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { FaGithub, FaGoogle } from 'react-icons/fa';
import { Link2, Loader2, Unlink } from 'lucide-react';

import { Button } from '../ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../ui/card';
import { useAuth } from '../../contexts/AuthContext';
import { describeError } from '../../services/auth';
import {
  LinkedProvider,
  listLinkedProviders,
  unlinkProvider,
} from '../../services/authApi';
import { apiOrigin } from '../../lib/apiBase';

const ALL_PROVIDERS: Array<{
  id: 'google' | 'github';
  name: string;
  icon: React.ReactNode;
}> = [
  { id: 'google', name: 'Google', icon: <FaGoogle className="h-5 w-5" /> },
  { id: 'github', name: 'GitHub', icon: <FaGithub className="h-5 w-5" /> },
];

/**
 * "Linked accounts" section in the profile page. Lists which OAuth
 * providers the user has connected, lets them connect new ones, and
 * lets them unlink an existing provider.
 */
export function LinkedAccountsCard() {
  const { refreshUser } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [linked, setLinked] = useState<LinkedProvider[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [busyProvider, setBusyProvider] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listLinkedProviders();
      setLinked(data);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // React to ?linked= / ?error= from the OAuth callback.
  useEffect(() => {
    const linkedProv = params.get('linked');
    const err = params.get('error');
    if (linkedProv) {
      setInfo(`Đã liên kết ${linkedProv} thành công.`);
      // strip the query param so the message doesn't reappear on refresh
      const next = new URLSearchParams(params);
      next.delete('linked');
      next.delete('error');
      navigate({ search: next.toString() ? `?${next}` : '' }, { replace: true });
      void load();
      void refreshUser();
    } else if (err) {
      setError(
        err === 'merge_required'
          ? 'Tài khoản OAuth này đã liên kết với một người dùng LivingDocs khác.'
          : describeError(new Error(err)),
      );
      const next = new URLSearchParams(params);
      next.delete('error');
      navigate({ search: next.toString() ? `?${next}` : '' }, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  const handleLink = (provider: 'google' | 'github') => {
    setBusyProvider(provider);
    window.location.href = `${apiOrigin}/api/v1/auth/oauth/${provider}/link/start`;
  };

  const handleUnlink = async (provider: 'google' | 'github') => {
    if (!window.confirm(`Gỡ liên kết ${provider} khỏi tài khoản?`)) return;
    setBusyProvider(provider);
    setError(null);
    try {
      await unlinkProvider(provider);
      setInfo(`Đã gỡ liên kết ${provider}.`);
      await load();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusyProvider(null);
    }
  };

  const linkedBy = (p: string) => linked.find((l) => l.provider === p);

  return (
    <Card>
      <CardHeader>
        <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Link2 className="h-5 w-5" />
        </div>
        <CardTitle>Tài khoản đã liên kết</CardTitle>
        <CardDescription>
          Kết nối Google hoặc GitHub để đăng nhập nhanh hơn và xác thực email tự động.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Đang tải…
          </div>
        ) : (
          ALL_PROVIDERS.map((p) => {
            const connection = linkedBy(p.id);
            return (
              <div
                key={p.id}
                className="flex items-center justify-between rounded-md border border-border p-3"
              >
                <div className="flex items-center gap-3">
                  <div className="text-foreground">{p.icon}</div>
                  <div>
                    <div className="font-medium">{p.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {connection
                        ? connection.providerEmail ?? 'Đã liên kết'
                        : 'Chưa liên kết'}
                    </div>
                  </div>
                </div>
                {connection ? (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busyProvider === p.id}
                    onClick={() => handleUnlink(p.id)}
                  >
                    <Unlink className="mr-2 h-4 w-4" />
                    Gỡ liên kết
                  </Button>
                ) : (
                  <Button
                    variant="default"
                    size="sm"
                    disabled={busyProvider === p.id}
                    onClick={() => handleLink(p.id)}
                  >
                    {busyProvider === p.id ? (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    ) : null}
                    Liên kết
                  </Button>
                )}
              </div>
            );
          })
        )}

        {error && (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}
        {info && (
          <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-300">
            {info}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
