import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Building2, Check, ChevronDown, Plus } from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../ui/dropdown-menu';
import { Button } from '../ui/button';
import { LoadingState, ErrorState } from '../ui/states';
import { listWorkspaces, Workspace } from '../../services/workspaces';
import { describeError } from '../../services/auth';
import { cn } from '../../lib/utils';

const STORAGE_KEY = 'livingdocs:last_workspace_id';

/**
 * Topbar dropdown that lets a user switch between workspaces without
 * losing their current page (we swap the workspaceId in the URL).
 *
 * The selected workspace id is persisted to localStorage so that
 * subsequent visits default to the last-used workspace.
 */
export function WorkspaceSwitcher() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const params = useParams<{ workspaceId?: string }>();

  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

  const activeId = params.workspaceId
    ?? readLast()
    ?? (workspaces[0]?.id ?? '');

  useEffect(() => {
    let alive = true;
    listWorkspaces()
      .then((list) => { if (alive) setWorkspaces(list); })
      .catch((err) => { if (alive) setError(describeError(err)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  // Persist last-used id whenever we see one in the URL.
  useEffect(() => {
    if (params.workspaceId) writeLast(params.workspaceId);
  }, [params.workspaceId]);

  // If we land on a workspace-less page and have a last-used one, the
  // DashboardPage can use the persisted id to scope its stats. We don't
  // navigate automatically to avoid surprising redirects.
  const active = workspaces.find((w) => w.id === activeId);

  function onPick(ws: Workspace) {
    writeLast(ws.id);
    setOpen(false);
    const next = swapWorkspaceId(location.pathname, ws.id);
    navigate(next + location.search + location.hash);
  }

  if (loading) {
    return (
      <Button variant="outline" size="sm" disabled className="gap-2">
        <Building2 className="h-4 w-4" />
        <LoadingState message="" />
      </Button>
    );
  }

  if (error) {
    return <ErrorState message={error} />;
  }

  if (workspaces.length === 0) {
    return (
      <Button
        variant="outline"
        size="sm"
        className="gap-2"
        onClick={() => navigate('/workspaces')}
      >
        <Plus className="h-4 w-4" />
        {t('workspaceSwitcher.create', 'New workspace')}
      </Button>
    );
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="gap-2">
          <Building2 className="h-4 w-4 text-muted-foreground" />
          <span className="max-w-[160px] truncate">
            {active?.name ?? t('workspaceSwitcher.pick', 'Pick workspace')}
          </span>
          <ChevronDown className="h-4 w-4 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[240px]">
        <DropdownMenuLabel>
          {t('workspaceSwitcher.label', 'Workspaces')}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {workspaces.map((w) => (
          <DropdownMenuItem
            key={w.id}
            onSelect={() => onPick(w)}
            className={cn('flex items-center justify-between gap-2')}
          >
            <div className="min-w-0">
              <div className="truncate font-medium">{w.name}</div>
              {w.description && (
                <div className="truncate text-xs text-muted-foreground">{w.description}</div>
              )}
            </div>
            {w.id === activeId && <Check className="h-4 w-4 shrink-0 text-primary" />}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => { setOpen(false); navigate('/workspaces'); }}>
          <Plus className="mr-2 h-4 w-4" />
          {t('workspaceSwitcher.manage', 'Manage workspaces')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function readLast(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeLast(id: string) {
  try {
    localStorage.setItem(STORAGE_KEY, id);
  } catch {
    /* non-fatal */
  }
}

/**
 * Replace the workspaceId segment in a workspace-scoped path. If the
 * current path is not workspace-scoped (e.g. `/dashboard`), the active
 * workspace is irrelevant and we return the path unchanged so we don't
 * accidentally break navigation.
 */
function swapWorkspaceId(pathname: string, newId: string): string {
  const m = pathname.match(/^\/workspaces\/([0-9a-f-]+)(\/.*)?$/i);
  if (!m) return pathname;
  const tail = m[2] ?? '';
  return `/workspaces/${newId}${tail}`;
}
