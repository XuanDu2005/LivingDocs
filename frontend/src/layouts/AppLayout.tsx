import { ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import {
  BookOpen,
  FileText,
  LayoutDashboard,
  Library,
  LogOut,
  Moon,
  Settings,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Sun,
  Users,
  Activity,
  AlertTriangle,
  GitBranch,
  Stethoscope,
  ScrollText,
  Megaphone,
  PlugZap,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { hasRole } from '../services/auth';
import { useTheme } from '../components/theme-provider';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { LanguageSwitcher } from '../components/LanguageSwitcher';
import { NotificationBell } from '../components/NotificationBell';
import { cn } from '../lib/utils';

interface AppLayoutProps {
  children: ReactNode;
}

interface NavItem {
  labelKey: string;
  to: string;
  icon: typeof BookOpen;
  authOnly?: boolean;
}

/** Items shown to every authenticated user. */
const WORKSPACE_NAV_ITEMS: NavItem[] = [
  { labelKey: 'nav.home', to: '/', icon: BookOpen },
  { labelKey: 'nav.dashboard', to: '/dashboard', icon: LayoutDashboard, authOnly: true },
  { labelKey: 'nav.workspaces', to: '/workspaces', icon: Users, authOnly: true },
  { labelKey: 'nav.documents', to: '/workspaces', icon: FileText, authOnly: true },
  { labelKey: 'nav.reviews', to: '/workspaces', icon: Shield, authOnly: true },
  { labelKey: 'nav.drift', to: '/workspaces', icon: AlertTriangle, authOnly: true },
  { labelKey: 'nav.knowledge', to: '/workspaces', icon: Library, authOnly: true },
  { labelKey: 'nav.aiSettings', to: '/ai-settings', icon: Sparkles, authOnly: true },
  { labelKey: 'nav.health', to: '/workspaces', icon: Stethoscope, authOnly: true },
];

/** Items shown only to ADMINs. Rendered in their own section. */
const ADMIN_NAV_ITEMS: NavItem[] = [
  { labelKey: 'nav.overview', to: '/admin', icon: LayoutDashboard },
  { labelKey: 'nav.users', to: '/admin/users', icon: Users },
  { labelKey: 'nav.roles', to: '/admin/roles', icon: ShieldCheck },
  { labelKey: 'nav.adminWorkspaces', to: '/admin/workspaces', icon: ScrollText },
  { labelKey: 'nav.adminNotifications', to: '/admin/notifications', icon: Megaphone },
  { labelKey: 'nav.integrations', to: '/admin/integrations', icon: PlugZap },
  { labelKey: 'nav.aiSettings', to: '/admin/ai-settings', icon: Sparkles },
  { labelKey: 'nav.auditRetention', to: '/admin/audit-retention', icon: ShieldAlert },
];

function buildWorkspaceItems(workspaceId?: string) {
  if (!workspaceId) return [];
  return [
    { labelKey: 'nav.documents', to: `/workspaces/${workspaceId}/documents`, icon: FileText },
    { labelKey: 'nav.reviews', to: `/workspaces/${workspaceId}/reviews`, icon: Shield },
    { labelKey: 'nav.drift', to: `/workspaces/${workspaceId}/drift`, icon: AlertTriangle },
    { labelKey: 'workspace_templates_title', to: `/workspaces/${workspaceId}/templates`, icon: Sparkles },
    { labelKey: 'nav.knowledge', to: `/workspaces/${workspaceId}/knowledge`, icon: Library },
    { labelKey: 'nav.aiSettings', to: `/workspaces/${workspaceId}/ai-settings`, icon: Sparkles },
    { labelKey: 'nav.integrations', to: `/workspaces/${workspaceId}/integrations`, icon: PlugZap },
    { labelKey: 'nav.health', to: `/workspaces/${workspaceId}/health`, icon: Stethoscope },
  ];
}

export function AppLayout({ children }: AppLayoutProps) {
  const { user, isAuthenticated, logout } = useAuth();
  const { resolved, setTheme } = useTheme();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();

  // extract workspaceId from path if present
  const wsMatch = location.pathname.match(/^\/workspaces\/([0-9a-f-]+)/i);
  const workspaceId = wsMatch?.[1];
  const wsItems = buildWorkspaceItems(workspaceId);

  const isAdmin = isAuthenticated && hasRole(user, 'ADMIN');

  // Regular users see the standard nav. Admins only see admin tooling.
  const workspaceItems = isAdmin
    ? []
    : WORKSPACE_NAV_ITEMS.filter((item) => {
        if (item.authOnly && !isAuthenticated) return false;
        return true;
      });
  const adminItems = isAdmin ? ADMIN_NAV_ITEMS : [];
  const showWorkspaceItems = !isAdmin && isAuthenticated && Boolean(workspaceId);

  function onLogout() {
    logout();
    navigate('/', { replace: true });
  }

  function toggleTheme() {
    setTheme(resolved === 'dark' ? 'light' : 'dark');
  }

  return (
    <div className="flex h-screen overflow-hidden bg-background text-foreground">
      {/* Sidebar */}
      <aside className="hidden w-60 shrink-0 flex-col border-r bg-card/40 md:flex">
        <div className="flex h-14 items-center gap-2 border-b px-4">
          <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <BookOpen className="h-4 w-4" />
          </div>
          <span className="text-base font-semibold tracking-tight">{t('common.appName')}</span>
        </div>
        <nav className="flex-1 space-y-6 overflow-y-auto p-3">
          {workspaceItems.length > 0 && (
            <div>
              <div className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t('nav.sectionWorkspace')}
              </div>
              <div className="space-y-0.5">
                {workspaceItems.map((item) => (
                  <SidebarLink
                    key={item.labelKey}
                    to={item.to}
                    icon={item.icon}
                    label={translateNavLabel(item.labelKey, t)}
                  />
                ))}
              </div>
            </div>
          )}

          {adminItems.length > 0 && (
            <div>
              <div className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t('nav.sectionAdministration')}
              </div>
              <div className="space-y-0.5">
                {adminItems.map((item) => (
                  <SidebarLink
                    key={item.labelKey}
                    to={item.to}
                    icon={item.icon}
                    label={translateNavLabel(item.labelKey, t)}
                  />
                ))}
              </div>
            </div>
          )}

          {showWorkspaceItems && workspaceId && (
            <div>
              <div className="mb-1 flex items-center justify-between px-2">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t('nav.sectionThisWorkspace')}
                </span>
                <Badge variant="muted" className="text-[10px]">
                  <GitBranch className="mr-1 h-3 w-3" />
                  {workspaceId.slice(0, 6)}
                </Badge>
              </div>
              <div className="space-y-0.5">
                {wsItems.map((item) => (
                  <SidebarLink
                    key={item.to}
                    to={item.to}
                    icon={item.icon}
                    label={translateNavLabel(item.labelKey, t)}
                  />
                ))}
              </div>
            </div>
          )}
        </nav>
        <div className="border-t p-3">
          {isAuthenticated && user ? (
            <div className="space-y-2">
              <div className="rounded-md bg-muted/50 px-3 py-2">
                <div className="truncate text-sm font-medium">
                  {user.displayName || user.email}
                </div>
                <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
                  {(user.roles && user.roles.length > 0) ? (
                    user.roles.map((r) => (
                      <Badge key={r} variant="muted" className="text-[10px]">{r}</Badge>
                    ))
                  ) : (
                    <span className="text-[10px]">{t('common.loading')}</span>
                  )}
                </div>
              </div>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  className="flex-1 justify-start"
                  onClick={toggleTheme}
                  aria-label={t('common.theme.toggle')}
                >
                  {resolved === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                  {resolved === 'dark' ? t('common.theme.light') : t('common.theme.dark')}
                </Button>
                <Button variant="ghost" size="sm" asChild>
                  <Link to="/profile">
                    <Settings className="h-4 w-4" />
                  </Link>
                </Button>
                <Button variant="ghost" size="sm" onClick={onLogout} aria-label={t('common.actions.logout')}>
                  <LogOut className="h-4 w-4" />
                </Button>
              </div>
              <NotificationBell />
              <LanguageSwitcher />
            </div>
          ) : (
            <div className="space-y-2">
              <Button asChild className="w-full" size="sm">
                <Link to="/login">{t('common.actions.login')}</Link>
              </Button>
              <Button asChild variant="outline" className="w-full" size="sm">
                <Link to="/register">{t('common.actions.signup')}</Link>
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="w-full justify-start"
                onClick={toggleTheme}
              >
                {resolved === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                {resolved === 'dark' ? t('common.theme.light') : t('common.theme.dark')} {t('common.theme.toggle')}
              </Button>
              <LanguageSwitcher />
            </div>
          )}
        </div>
      </aside>

      {/* Main */}
      <div className="flex flex-1 flex-col overflow-hidden">
        <header className="flex h-14 items-center justify-between border-b bg-card/30 px-4 md:px-6">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm text-muted-foreground">{t('common.tagline')}</span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden"
              onClick={toggleTheme}
              aria-label={t('common.theme.toggle')}
            >
              {resolved === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </Button>
            {!isAuthenticated && (
              <div className="flex gap-2 md:hidden">
                <Button asChild size="sm" variant="ghost">
                  <Link to="/login">{t('common.actions.login')}</Link>
                </Button>
              </div>
            )}
          </div>
        </header>
        <main className="flex-1 overflow-y-auto p-4 md:p-8">
          <div className="mx-auto w-full max-w-7xl animate-fade-in">{children}</div>
        </main>
      </div>
    </div>
  );
}

function SidebarLink({
  to,
  icon: Icon,
  label,
}: {
  to: string;
  icon: typeof BookOpen;
  label: string;
}) {
  return (
    <NavLink
      to={to}
      end={to === '/' || to === '/admin'}
      className={({ isActive }) =>
        cn(
          'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors',
          isActive
            ? 'bg-primary/15 text-primary'
            : 'text-muted-foreground hover:bg-muted hover:text-foreground',
        )
      }
    >
      <Icon className="h-4 w-4" />
      {label}
    </NavLink>
  );
}

/**
 * Inline labels for nav items that intentionally use the templates key
 * rather than the generic {@code nav.*} namespace, because the string
 * differs from the top-level "Templates" entry. Kept in this file to
 * keep the nav structure readable.
 */
function translateNavLabel(key: string, t: (k: string) => string): string {
  if (key === 'workspace_templates_title') return t('templates.title');
  return t(key);
}
