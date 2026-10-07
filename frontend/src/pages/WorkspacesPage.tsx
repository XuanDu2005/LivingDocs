import { FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus, Users as UsersIcon } from 'lucide-react';
import { PageTitle } from '../components/PageTitle';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Badge } from '../components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import {
  createWorkspace,
  listWorkspaces,
  Workspace,
} from '../services/workspaces';
import { format } from 'date-fns';

export default function WorkspacesPage() {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<boolean>(false);

  const [name, setName] = useState<string>('');
  const [slug, setSlug] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  const [slugTouched, setSlugTouched] = useState<boolean>(false);
  const [creating, setCreating] = useState<boolean>(false);
  const [createError, setCreateError] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const list = await listWorkspaces();
      setWorkspaces(list);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  useEffect(() => {
    if (!slugTouched) {
      setSlug(name.toLowerCase().trim().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, ''));
    }
  }, [name, slugTouched]);

  async function onCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setCreating(true);
    setCreateError(null);
    try {
      await createWorkspace({ name, slug, description: description || undefined });
      setName(''); setSlug(''); setDescription(''); setSlugTouched(false);
      setOpen(false);
      await refresh();
    } catch (err) {
      setCreateError(describeError(err));
    } finally {
      setCreating(false);
    }
  }

  return (
    <div>
      <PageTitle
        title={t('workspaces.title')}
        subtitle={t('workspaces.subtitle', { name: user?.displayName ?? user?.email ?? '' })}
        action={
          <Dialog open={open} onOpenChange={setOpen}>
            <DialogTrigger asChild>
              <Button>
                <Plus className="mr-1 h-4 w-4" />
                {t('workspaces.newCta')}
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>{t('workspaces.createTitle')}</DialogTitle>
                <DialogDescription>{t('workspaces.createDesc')}</DialogDescription>
              </DialogHeader>
              <form onSubmit={onCreate} className="space-y-3">
                <div className="space-y-1.5">
                  <Label htmlFor="ws-name">{t('workspaces.fieldName')}</Label>
                  <Input id="ws-name" value={name} onChange={(e) => setName(e.target.value)}
                    required minLength={2} maxLength={120} />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="ws-slug">{t('workspaces.fieldSlug')}</Label>
                  <Input id="ws-slug" value={slug}
                    onChange={(e) => { setSlug(e.target.value); setSlugTouched(true); }}
                    required minLength={2} maxLength={140} pattern="^[a-z0-9][a-z0-9-]*$" />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="ws-desc">{t('workspaces.fieldDescription')}</Label>
                  <Textarea id="ws-desc" value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    maxLength={500} rows={2} />
                </div>
                {createError && (
                  <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                    {createError}
                  </div>
                )}
                <DialogFooter>
                  <Button type="button" variant="outline" onClick={() => setOpen(false)}>
                    {t('common.cancel')}
                  </Button>
                  <Button type="submit" disabled={creating}>
                    {creating ? t('workspaces.creating') : t('common.create')}
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        }
      />

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('workspaces.loading')} />}

      {!loading && !error && workspaces.length === 0 && (
        <EmptyState
          icon={<UsersIcon className="h-8 w-8" />}
          title={t('workspaces.emptyTitle')}
          description={t('workspaces.emptyDesc')}
          action={
            <Button onClick={() => setOpen(true)}>
              <Plus className="mr-1 h-4 w-4" />
              {t('workspaces.newCta')}
            </Button>
          }
        />
      )}

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {workspaces.map((w) => (
          <Card key={w.id} className="group transition-shadow hover:shadow-md">
            <CardHeader>
              <div className="flex items-center justify-between">
                <Badge variant="muted" className="font-mono text-[10px]">{w.slug}</Badge>
                <span className="text-xs text-muted-foreground">
                  {format(new Date(w.createdAt), 'MMM d, yyyy')}
                </span>
              </div>
              <CardTitle className="text-base">
                <Link to={`/workspaces/${w.id}`} className="hover:text-primary">
                  {w.name}
                </Link>
              </CardTitle>
              {w.description && (
                <CardDescription className="line-clamp-2">{w.description}</CardDescription>
              )}
            </CardHeader>
            <CardContent>
              <Button variant="outline" size="sm" asChild>
                <Link to={`/workspaces/${w.id}`}>{t('workspaces.openWorkspace')}</Link>
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}