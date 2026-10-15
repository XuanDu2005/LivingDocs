import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  BookOpen,
  Check,
  ChevronLeft,
  ChevronRight,
  Github,
  Plus,
  Search,
  Sparkles,
  Users,
} from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Textarea } from '../../components/ui/textarea';
import { Label } from '../../components/ui/label';
import { Badge } from '../../components/ui/badge';
import { LoadingState, ErrorState } from '../../components/ui/states';
import { useAuth } from '../../contexts/AuthContext';
import { describeError } from '../../services/auth';
import { createWorkspace, Workspace } from '../../services/workspaces';
import { githubApi, GithubConnection, GithubRepositoryCatalogItem } from '../../services/github';

const STORAGE_KEY = 'livingdocs:onboarding:v1';
const MAX_DISPLAYS = 2; // Show wizard at most twice before staying hidden

type Step = 'welcome' | 'workspace' | 'github' | 'repo' | 'done';

interface WizardState {
  step: Step;
  workspace: Workspace | null;
  github: GithubConnection | null;
  repo: GithubRepositoryCatalogItem | null;
}

const STEP_ORDER: Step[] = ['welcome', 'workspace', 'github', 'repo', 'done'];

/**
 * Reads the onboarding counter from localStorage.
 * Returns the number of times the wizard has been shown to the user.
 */
function readCount(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === 'done') return MAX_DISPLAYS; // legacy: treat finished wizards as fully hidden
    const n = Number(raw ?? '0');
    return Number.isFinite(n) && n >= 0 ? n : 0;
  } catch {
    return 0;
  }
}

function incrementCount() {
  try {
    const current = readCount();
    if (current >= MAX_DISPLAYS) return; // already capped
    localStorage.setItem(STORAGE_KEY, String(current + 1));
  } catch {
    /* localStorage may be disabled — non-fatal */
  }
}

function writeDoneFlag() {
  try {
    localStorage.setItem(STORAGE_KEY, 'done');
  } catch {
    /* localStorage may be disabled — non-fatal */
  }
}

/**
 * First-run wizard that walks a brand-new user through the 4 setup steps
 * (welcome → workspace → GitHub → repository) in a single modal flow.
 *
 * <p>Visibility rules:
 * <ul>
 *   <li>Skipped entirely for {@code ADMIN} users (they have their own flow).</li>
 *   <li>Shown at most {@link MAX_DISPLAYS} times per browser (default 2).</li>
 *   <li>Hidden permanently once the user reaches the "done" step — a
 *       {@code done} flag is written to localStorage.</li>
 *   <li>Hidden permanently once the display budget is exhausted — the
 *       count is incremented each time the wizard is opened.</li>
 * </ul>
 *
 * The user can dismiss at any time by clicking outside, pressing Escape,
 * or hitting "Skip". Dismissing only costs one display; it does NOT mark
 * the wizard as completed.
 */
export function FirstRunWizard() {
  const { isAuthenticated, user } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [open, setOpen] = useState(false);
  const [state, setState] = useState<WizardState>({
    step: 'welcome',
    workspace: null,
    github: null,
    repo: null,
  });

  // Open the wizard on first authenticated mount while the user hasn't
  // exhausted the display budget (default: 2 times). Once they finish the
  // wizard, the `done` flag is set and it never reappears.
  useEffect(() => {
    if (!isAuthenticated) return;
    if (readCount() >= MAX_DISPLAYS) return;
    setOpen(true);
    incrementCount();
  }, [isAuthenticated]);

  function close(reason: 'dismiss' | 'finish' = 'dismiss') {
    setOpen(false);
    if (reason === 'finish') writeDoneFlag();
  }

  function next() {
    const idx = STEP_ORDER.indexOf(state.step);
    if (idx < STEP_ORDER.length - 1) {
      setState((prev) => ({ ...prev, step: STEP_ORDER[idx + 1] }));
    }
  }

  function back() {
    const idx = STEP_ORDER.indexOf(state.step);
    if (idx > 0) {
      setState((prev) => ({ ...prev, step: STEP_ORDER[idx - 1] }));
    }
  }

  // Don't show the wizard for admins — they have their own flow.
  const isAdmin = (user?.roles ?? []).includes('ADMIN');
  const shouldShow = isAuthenticated && !isAdmin && open;

  return (
    <Dialog
      open={shouldShow}
      onOpenChange={(next) => {
        if (!next) close('dismiss');
      }}
    >
      <DialogContent
        className="max-w-2xl"
        onPointerDownOutside={(e) => {
          // Treat a click outside the dialog as "skip for now" rather than
          // trapping the user in a sticky modal.
          e.preventDefault();
          close('dismiss');
        }}
        onInteractOutside={(e) => {
          e.preventDefault();
          close('dismiss');
        }}
      >
        <StepHeader step={state.step} t={t} />
        <div className="min-h-[280px]">
          {state.step === 'welcome' && <StepWelcome t={t} onNext={next} onSkip={() => close('dismiss')} />}
          {state.step === 'workspace' && (
            <StepCreateWorkspace
              t={t}
              onBack={back}
              onCreated={(workspace) => {
                setState((prev) => ({ ...prev, workspace }));
                next();
              }}
            />
          )}
          {state.step === 'github' && (
            <StepConnectGitHub
              t={t}
              workspaceId={state.workspace?.id}
              onBack={back}
              onConnected={(conn) => {
                setState((prev) => ({ ...prev, github: conn }));
                next();
              }}
              onSkip={() => {
                setState((prev) => ({ ...prev, github: null }));
                next();
              }}
            />
          )}
          {state.step === 'repo' && (
            <StepPickRepo
              t={t}
              workspaceId={state.workspace?.id}
              onBack={back}
              onPicked={(repo) => {
                setState((prev) => ({ ...prev, repo }));
                setState((prev) => ({ ...prev, step: 'done' }));
              }}
              onSkip={() => {
                setState((prev) => ({ ...prev, step: 'done' }));
              }}
            />
          )}
          {state.step === 'done' && (
            <StepDone
              t={t}
              workspaceId={state.workspace?.id}
              onClose={() => close('finish')}
              onGoToDashboard={() => {
                close('finish');
                navigate('/dashboard');
              }}
            />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

function StepHeader({ step, t }: { step: Step; t: (k: string) => string }) {
  const labels: Record<Step, { title: string; subtitle: string; index: number }> = {
    welcome: { title: t('onboarding.welcome.title'), subtitle: t('onboarding.welcome.subtitle'), index: 1 },
    workspace: { title: t('onboarding.workspace.title'), subtitle: t('onboarding.workspace.subtitle'), index: 2 },
    github: { title: t('onboarding.github.title'), subtitle: t('onboarding.github.subtitle'), index: 3 },
    repo: { title: t('onboarding.repo.title'), subtitle: t('onboarding.repo.subtitle'), index: 4 },
    done: { title: t('onboarding.done.title'), subtitle: t('onboarding.done.subtitle'), index: 5 },
  };
  const meta = labels[step];
  return (
    <DialogHeader>
      <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
        <Sparkles className="h-3.5 w-3.5 text-primary" />
        {meta.index}/4
      </div>
      <DialogTitle>{meta.title}</DialogTitle>
      <DialogDescription>{meta.subtitle}</DialogDescription>
    </DialogHeader>
  );
}

function StepWelcome({ t, onNext, onSkip }: { t: (k: string) => string; onNext: () => void; onSkip: () => void }) {
  const { user } = useAuth();
  return (
    <div className="space-y-4">
      <div className="rounded-lg border bg-muted/30 p-4">
        <h3 className="text-base font-semibold">
          {t('onboarding.welcome.hello').replace('{{name}}', user?.displayName || user?.email || '')}
        </h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {t('onboarding.welcome.intro')}
        </p>
      </div>
      <ul className="space-y-2 text-sm">
        <li className="flex items-center gap-2">
          <Badge variant="muted" className="h-6 w-6 justify-center">1</Badge>
          <Users className="h-4 w-4 text-muted-foreground" />
          {t('onboarding.welcome.step1')}
        </li>
        <li className="flex items-center gap-2">
          <Badge variant="muted" className="h-6 w-6 justify-center">2</Badge>
          <Github className="h-4 w-4 text-muted-foreground" />
          {t('onboarding.welcome.step2')}
        </li>
        <li className="flex items-center gap-2">
          <Badge variant="muted" className="h-6 w-6 justify-center">3</Badge>
          <BookOpen className="h-4 w-4 text-muted-foreground" />
          {t('onboarding.welcome.step3')}
        </li>
        <li className="flex items-center gap-2">
          <Badge variant="muted" className="h-6 w-6 justify-center">4</Badge>
          <Sparkles className="h-4 w-4 text-muted-foreground" />
          {t('onboarding.welcome.step4')}
        </li>
      </ul>
      <DialogFooter>
        <Button variant="ghost" onClick={onSkip}>{t('onboarding.welcome.skip')}</Button>
        <Button onClick={onNext}>
          {t('onboarding.welcome.getStarted')}
          <ChevronRight className="ml-1 h-4 w-4" />
        </Button>
      </DialogFooter>
    </div>
  );
}

function StepCreateWorkspace({
  t,
  onBack,
  onCreated,
}: {
  t: (k: string) => string;
  onBack: () => void;
  onCreated: (w: Workspace) => void;
}) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [slugTouched, setSlugTouched] = useState(false);

  useEffect(() => {
    if (!slugTouched) {
      setSlug(name.toLowerCase().trim().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, ''));
    }
  }, [name, slugTouched]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const ws = await createWorkspace({ name, slug, description: description || undefined });
      onCreated(ws);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="wiz-ws-name">{t('onboarding.workspace.nameLabel')}</Label>
        <Input
          id="wiz-ws-name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
          minLength={2}
          maxLength={120}
          placeholder={t('onboarding.workspace.namePlaceholder')}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="wiz-ws-slug">{t('onboarding.workspace.slugLabel')}</Label>
        <Input
          id="wiz-ws-slug"
          value={slug}
          onChange={(e) => { setSlug(e.target.value); setSlugTouched(true); }}
          required
          pattern="^[a-z0-9][a-z0-9-]*$"
        />
        <p className="text-xs text-muted-foreground">{t('onboarding.workspace.slugHint')}</p>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="wiz-ws-desc">{t('onboarding.workspace.descLabel')}</Label>
        <Textarea
          id="wiz-ws-desc"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={2}
          maxLength={500}
          placeholder={t('onboarding.workspace.descPlaceholder')}
        />
      </div>
      {error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
          {error}
        </div>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onBack}>
          <ChevronLeft className="mr-1 h-4 w-4" /> {t('onboarding.workspace.back')}
        </Button>
        <Button type="submit" disabled={submitting}>
          {submitting ? t('onboarding.workspace.creating') : t('onboarding.workspace.create')}
          {!submitting && <ChevronRight className="ml-1 h-4 w-4" />}
        </Button>
      </DialogFooter>
    </form>
  );
}

function StepConnectGitHub({
  t,
  workspaceId,
  onBack,
  onConnected,
  onSkip,
}: {
  t: (k: string) => string;
  workspaceId?: string;
  onBack: () => void;
  onConnected: (conn: GithubConnection) => void;
  onSkip: () => void;
}) {
  const [connection, setConnection] = useState<GithubConnection | null>(null);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    githubApi.getConnection()
      .then((c) => { if (alive) setConnection(c); })
      .catch(() => undefined)
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  async function onConnect() {
    setConnecting(true);
    setError(null);
    try {
      const { url, state } = await githubApi.getAuthorizeUrl();
      sessionStorage.setItem('github-oauth-state', state);
      window.location.href = url;
    } catch (err) {
      setError(describeError(err));
      setConnecting(false);
    }
  }

  // After OAuth callback, the page reloads with the connection. Once we
  // see one, auto-advance so the user doesn't have to click Next manually.
  useEffect(() => {
    if (connection && connection.status === 'ACTIVE' && workspaceId) {
      const tmo = setTimeout(() => onConnected(connection), 400);
      return () => clearTimeout(tmo);
    }
  }, [connection, workspaceId, onConnected]);

  const isActive = connection?.status === 'ACTIVE';

  return (
    <div className="space-y-4">
      {loading ? (
        <LoadingState message={t('onboarding.github.checking')} />
      ) : isActive ? (
        <div className="flex items-center gap-2 rounded-md border border-primary/40 bg-primary/5 p-3 text-sm">
          <Check className="h-4 w-4 text-primary" />
          {t('onboarding.github.connectedAs').replace('{{login}}', connection!.githubLogin)}
        </div>
      ) : (
        <div className="rounded-md border p-4">
          <p className="text-sm text-muted-foreground">
            {t('onboarding.github.connectDesc')}
          </p>
          <Button onClick={onConnect} disabled={connecting} className="mt-3">
            <Github className="mr-1 h-4 w-4" />
            {connecting ? t('onboarding.github.redirecting') : t('onboarding.github.connect')}
          </Button>
          {error && (
            <div className="mt-3 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
              {error}
            </div>
          )}
        </div>
      )}
      <DialogFooter>
        <Button variant="ghost" onClick={onBack}>
          <ChevronLeft className="mr-1 h-4 w-4" /> {t('onboarding.github.back')}
        </Button>
        <Button variant="outline" onClick={onSkip}>{t('onboarding.github.skip')}</Button>
        <Button onClick={() => connection && onConnected(connection)} disabled={!isActive}>
          {t('onboarding.github.next')} <ChevronRight className="ml-1 h-4 w-4" />
        </Button>
      </DialogFooter>
    </div>
  );
}

function StepPickRepo({
  t,
  workspaceId,
  onBack,
  onPicked,
  onSkip,
}: {
  t: (k: string) => string;
  workspaceId?: string;
  onBack: () => void;
  onPicked: (repo: GithubRepositoryCatalogItem) => void;
  onSkip: () => void;
}) {
  const [catalog, setCatalog] = useState<GithubRepositoryCatalogItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [connectingId, setConnectingId] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    githubApi.listCatalog()
      .then((items) => { if (alive) setCatalog(items); })
      .catch((err) => { if (alive) setError(describeError(err)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return catalog.slice(0, 20);
    return catalog.filter(
      (r) => r.fullName.toLowerCase().includes(q) || r.description?.toLowerCase().includes(q),
    ).slice(0, 20);
  }, [catalog, search]);

  async function onPick(repo: GithubRepositoryCatalogItem) {
    if (!workspaceId) return;
    setConnectingId(repo.githubId);
    try {
      await githubApi.connectRepository(workspaceId, repo.githubId, repo.defaultBranch);
      onPicked(repo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setConnectingId(null);
    }
  }

  return (
    <div className="space-y-3">
      <div className="relative">
        <Search className="pointer-events-none absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          placeholder={t('onboarding.repo.searchPlaceholder')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-8"
        />
      </div>
      {loading ? (
        <LoadingState message={t('onboarding.repo.loading')} />
      ) : error ? (
        <ErrorState message={error} />
      ) : filtered.length === 0 ? (
        <p className="rounded-md border bg-muted/30 p-3 text-sm text-muted-foreground">
          {t('onboarding.repo.empty')}
        </p>
      ) : (
        <ul className="max-h-64 space-y-1 overflow-y-auto rounded-md border p-1">
          {filtered.map((r) => (
            <li key={r.githubId}>
              <button
                type="button"
                onClick={() => onPick(r)}
                disabled={connectingId !== null}
                className="flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted disabled:opacity-50"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{r.fullName}</div>
                  {r.description && (
                    <div className="truncate text-xs text-muted-foreground">{r.description}</div>
                  )}
                </div>
                <Plus className="h-4 w-4 shrink-0 text-muted-foreground" />
              </button>
            </li>
          ))}
        </ul>
      )}
      <DialogFooter>
        <Button variant="ghost" onClick={onBack}>
          <ChevronLeft className="mr-1 h-4 w-4" /> {t('onboarding.repo.back')}
        </Button>
        <Button variant="outline" onClick={onSkip}>{t('onboarding.repo.skip')}</Button>
      </DialogFooter>
    </div>
  );
}

function StepDone({
  t,
  workspaceId,
  onClose,
  onGoToDashboard,
}: {
  t: (k: string) => string;
  workspaceId?: string;
  onClose: () => void;
  onGoToDashboard: () => void;
}) {
  return (
    <div className="space-y-4 text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Check className="h-6 w-6" />
      </div>
      <h3 className="text-lg font-semibold">{t('onboarding.done.title')}</h3>
      <p className="text-sm text-muted-foreground">
        {t('onboarding.done.body')}
      </p>
      <DialogFooter className="sm:justify-center">
        <Button variant="outline" onClick={onClose}>{t('onboarding.done.close')}</Button>
        <Button onClick={onGoToDashboard}>
          {t('onboarding.done.goToDashboard')}
          <ChevronRight className="ml-1 h-4 w-4" />
        </Button>
      </DialogFooter>
      {workspaceId && (
        <p className="text-xs text-muted-foreground">
          {t('onboarding.done.seeLater')}{' '}
          <a href={`/workspaces/${workspaceId}/documents`} className="text-primary hover:underline">
            Documents
          </a>.
        </p>
      )}
    </div>
  );
}
