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
  Sparkles,
  Sun,
  Users,
  Activity,
  AlertTriangle,
  GitBranch,
  Stethoscope,
} from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../components/theme-provider';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { cn } from '../lib/utils';

interface AppLayoutProps {
  children: ReactNode;
}

interface NavItem {
  label: string;
  to: string;
  icon: typeof BookOpen;
  authOnly?: boolean;
  adminOnly?: boolean;
}

const NAV_ITEMS: NavItem[] = [
  { label: 'Home', to: '/', icon: BookOpen },
  { label: 'Dashboard', to: '/dashboard', icon: LayoutDashboard, authOnly: true },
  { label: 'Workspaces', to: '/workspaces', icon: Users, authOnly: true },
  { label: 'Documents', to: '/workspaces', icon: FileText, authOnly: true },
  { label: 'Reviews', to: '/workspaces', icon: Shield, authOnly: true },
  { label: 'Drift', to: '/workspaces', icon: AlertTriangle, authOnly: true },
  { label: 'Knowledge', to: '/workspaces', icon: Library, authOnly: true },
  { label: 'Health', to: '/workspaces', icon: Stethoscope, authOnly: true },
  { label: 'Admin', to: '/admin/users', icon: Settings, adminOnly: true },
];

function buildWorkspaceItems(workspaceId?: string) {
  if (!workspaceId) return [];
  return [
    { label: 'Documents', to: `/workspaces/${workspaceId}/documents`, icon: FileText },
    { label: 'Reviews', to: `/workspaces/${workspaceId}/reviews`, icon: Shield },
    { label: 'Drift alerts', to: `/workspaces/${workspaceId}/drift`, icon: AlertTriangle },
    { label: 'Templates', to: `/workspaces/${workspaceId}/templates`, icon: Sparkles },
    { label: 'Knowledge base', to: `/workspaces/${workspaceId}/knowledge`, icon: Library },
    { label: 'Health dashboard', to: `/workspaces/${workspaceId}/health`, icon: Stethoscope },
  ];
}

export function AppLayout({ children }: AppLayoutProps) {
  const { user, isAuthenticated, logout } = useAuth();
  const { resolved, setTheme } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();

  // extract workspaceId from path if present
  const wsMatch = location.pathname.match(/^\/workspaces\/([0-9a-f-]+)/i);
  const workspaceId = wsMatch?.[1];
  const wsItems = buildWorkspaceItems(workspaceId);

  const topItems = NAV_ITEMS.filter((item) => {
    if (item.authOnly && !isAuthenticated) return false;
    if (item.adminOnly && user?.role !== 'ADMIN') return false;
    return true;
  });

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
          <span className="text-base font-semibold tracking-tight">LivingDocs</span>
        </div>
        <nav className="flex-1 space-y-6 overflow-y-auto p-3">
          <div>
            <div className="mb-1 px-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              Workspace
            </div>
            <div className="space-y-0.5">
              {topItems.map((item) => (
                <SidebarLink key={item.label} to={item.to} icon={item.icon} label={item.label} />
              ))}
            </div>
          </div>
          {isAuthenticated && workspaceId && (
            <div>
              <div className="mb-1 flex items-center justify-between px-2">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                  This workspace
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
                    label={item.label}
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
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Badge variant="muted" className="text-[10px]">
                    {user.role}
                  </Badge>
                </div>
              </div>
              <div className="flex gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  className="flex-1 justify-start"
                  onClick={toggleTheme}
                  aria-label="Toggle theme"
                >
                  {resolved === 'dark' ? (
                    <Sun className="h-4 w-4" />
                  ) : (
                    <Moon className="h-4 w-4" />
                  )}
                  {resolved === 'dark' ? 'Light' : 'Dark'}
                </Button>
                <Button variant="ghost" size="sm" asChild>
                  <Link to="/profile">
                    <Settings className="h-4 w-4" />
                  </Link>
                </Button>
                <Button variant="ghost" size="sm" onClick={onLogout}>
                  <LogOut className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ) : (
            <div className="space-y-2">
              <Button asChild className="w-full" size="sm">
                <Link to="/login">Log in</Link>
              </Button>
              <Button asChild variant="outline" className="w-full" size="sm">
                <Link to="/register">Sign up</Link>
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="w-full justify-start"
                onClick={toggleTheme}
              >
                {resolved === 'dark' ? (
                  <Sun className="h-4 w-4" />
                ) : (
                  <Moon className="h-4 w-4" />
                )}
                {resolved === 'dark' ? 'Light' : 'Dark'} mode
              </Button>
            </div>
          )}
        </div>
      </aside>

      {/* Main */}
      <div className="flex flex-1 flex-col overflow-hidden">
        <header className="flex h-14 items-center justify-between border-b bg-card/30 px-4 md:px-6">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm text-muted-foreground">
              Living documentation that lives with your code
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden"
              onClick={toggleTheme}
              aria-label="Toggle theme"
            >
              {resolved === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </Button>
            {!isAuthenticated && (
              <div className="flex gap-2 md:hidden">
                <Button asChild size="sm" variant="ghost">
                  <Link to="/login">Log in</Link>
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
      end={to === '/'}
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
