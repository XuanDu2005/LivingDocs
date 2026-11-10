import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  Github,
  GitBranch,
  GitCommit,
  RefreshCw,
  Unplug,
  ExternalLink,
  Search,
  X,
  AlertCircle,
  Loader2,
  GitPullRequest,
  CheckCircle2,
  Sparkles,
  Code2,
  Shield,
  Play,
  BookOpen,
  AlertTriangle,
  Boxes,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { ErrorState, EmptyState, LoadingState } from '../components/ui/states';
import { githubApi, Repository, PullRequest, Commit, RepositoryModule } from '../services/github';
import { codeApi, CodeEntity } from '../services/knowledge';
import { documentsApi } from '../services/documents';
import { driftApi } from '../services/drift';
import { docSyncApi, DocScanResult } from '../services/docSync';
import { aiApi, BulkGenerationJobStatus } from '../services/ai';
import { DocSyncPreviewModal } from '../components/DocSyncPreviewModal';
import ImpactPanel from '../components/DependencyImpactPanel';
import { describeError } from '../services/auth';
import { usePermission } from '../hooks/usePermission';
import { PermissionCodes } from '../services/permissions';

/**
 * Per-workspace repository browser.
 *
 * Responsibilities (Developer / Author):
 *   1. Browse the GitHub catalog (or paste a URL) and add a repository.
 *   2. See the connection status, default branch, last sync time.
 *   3. Manually re-sync a repository.
 *   4. View PRs and commits ingested from the repository.
 *   5. Disconnect a repository (revokes the link; GitHub side is untouched).
 *   6. Trigger an AI documentation generation sweep from the selected repo.
 */
export default function RepositoryPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const canManageAutoUpdate = usePermission(
    PermissionCodes.REPOSITORY_AUTO_UPDATE_MANAGE,
    PermissionCodes.MODULE_AUTO_UPDATE_MANAGE,
  );
  // The impact analysis of a code entity reads the dependency graph.
  // It is a separate capability from the auto-update toggles, and it
  // is only rendered for a reader who holds it.
  const canViewImpact = usePermission(PermissionCodes.DEPENDENCY_GRAPH_VIEW);

  const [repos, setRepos] = useState<Repository[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [search, setSearch] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'CONNECTED' | 'ERROR' | 'DISCONNECTED' | 'ARCHIVED'>('ALL');

  // Connect dialog state
  const [showConnect, setShowConnect] = useState<boolean>(false);
  const [tab, setTab] = useState<'catalog' | 'url'>('catalog');
  const [catalog, setCatalog] = useState<Awaited<ReturnType<typeof githubApi.listCatalog>>>([]);
  const [catalogLoading, setCatalogLoading] = useState<boolean>(false);
  const [catalogErr, setCatalogErr] = useState<string | null>(null);
  const [catalogSearch, setCatalogSearch] = useState<string>('');
  const [urlInput, setUrlInput] = useState<string>('');
  const [busy, setBusy] = useState<boolean>(false);
  const [githubConnected, setGithubConnected] = useState<boolean | null>(null);
  // Repositories ticked in the catalog dialog, connected in one go.
  const [selectedCatalogIds, setSelectedCatalogIds] = useState<number[]>([]);

  // Detail panel state
  const [selected, setSelected] = useState<Repository | null>(null);
  const [pulls, setPulls] = useState<PullRequest[]>([]);
  const [prsLoading, setPrsLoading] = useState<boolean>(false);
  const [commits, setCommits] = useState<Commit[]>([]);
  const [commitsLoading, setCommitsLoading] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<'pulls' | 'commits' | 'entities' | 'modules'>('pulls');

  // Auto-update configuration (repository level + per module)
  const [modules, setModules] = useState<RepositoryModule[]>([]);
  const [modulesLoading, setModulesLoading] = useState<boolean>(false);
  const [modulesError, setModulesError] = useState<string | null>(null);

  // Code entities state
  const [entities, setEntities] = useState<CodeEntity[]>([]);
  const [entitiesLoading, setEntitiesLoading] = useState<boolean>(false);
  const [entitySearch, setEntitySearch] = useState<string>('');
  const [entityTypeFilter, setEntityTypeFilter] = useState<string>('ALL');
  const [entityDetail, setEntityDetail] = useState<CodeEntity | null>(null);

  // Generate all docs state
  const [generatingAll, setGeneratingAll] = useState<boolean>(false);
  const [generateResult, setGenerateResult] = useState<string | null>(null);

  // Doc sync state
  const [docScanResult, setDocScanResult] = useState<DocScanResult | null>(null);
  const [scanningDocs, setScanningDocs] = useState<boolean>(false);
  const [docScanError, setDocScanError] = useState<string | null>(null);
  const [previewingFile, setPreviewingFile] = useState<string | null>(null);
  const [prUrl, setPrUrl] = useState<string | null>(null);

  // Coverage stats state
  const [coverageStats, setCoverageStats] = useState<{
    total: number;
    documented: number;
    undocumented: number;
  } | null>(null);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await githubApi.listRepositories(workspaceId);
      setRepos(list);
      if (selected && !list.find((r) => r.id === selected.id)) {
        setSelected(null);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  async function checkConnection() {
    try {
      const conn = await githubApi.getConnection();
      setGithubConnected(!!conn && conn.status === 'ACTIVE');
    } catch {
      setGithubConnected(false);
    }
  }

  useEffect(() => {
    void load();
    void checkConnection();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const filteredRepos = useMemo(() => {
    return repos.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
      if (search.trim()) {
        const q = search.toLowerCase();
        if (
          !r.fullName.toLowerCase().includes(q) &&
          !r.owner.toLowerCase().includes(q) &&
          !(r.description || '').toLowerCase().includes(q)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [repos, search, statusFilter]);

  async function openConnect() {
    if (!workspaceId) return;

    // Check if GitHub is connected first - if not, redirect to OAuth
    let isConnected = githubConnected;
    if (isConnected === null) {
      try {
        const conn = await githubApi.getConnection();
        isConnected = !!conn && conn.status === 'ACTIVE';
        setGithubConnected(isConnected);
      } catch {
        isConnected = false;
      }
    }

    if (!isConnected) {
      // Not connected - start OAuth flow immediately
      startGithubOAuth();
      return;
    }

    setShowConnect(true);
    setTab('catalog');
    setCatalogErr(null);
    setCatalogSearch('');
    setSelectedCatalogIds([]);
    if (catalog.length === 0) {
      try {
        setCatalogLoading(true);
        const list = await githubApi.listCatalog();
        setCatalog(list);
      } catch (err) {
        setCatalogErr(describeError(err));
      } finally {
        setCatalogLoading(false);
      }
    }
  }

  async function connectFromCatalog(githubId: number) {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const repo = await githubApi.connectRepository(workspaceId, githubId);
      setInfo(t('repository.connected', { name: repo.fullName }));
      setShowConnect(false);
      await load();
      setSelected(repo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Connect every repository ticked in the catalog.
   *
   * <p>Each connect is independent: one repository that fails does not
   * abort the rest, because a partial success is far more useful than an
   * all-or-nothing operation.
   */
  async function connectSelectedFromCatalog() {
    if (!workspaceId || selectedCatalogIds.length === 0) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    const succeeded: string[] = [];
    const failed: { name: string; reason: string }[] = [];
    for (const githubId of selectedCatalogIds) {
      const label = catalog.find((c) => c.githubId === githubId)?.fullName ?? String(githubId);
      try {
        const repo = await githubApi.connectRepository(workspaceId, githubId);
        succeeded.push(repo.fullName);
      } catch (err) {
        failed.push({ name: label, reason: describeError(err) });
      }
    }
    setSelectedCatalogIds([]);
    await load();
    if (succeeded.length > 0) {
      setInfo(
        t('repository.connectedMultiple', {
          count: succeeded.length,
          defaultValue: `Connected ${succeeded.length} repository(ies).`,
        }),
      );
    }
    if (failed.length > 0) {
      setError(
        t('repository.connectFailed', {
          names: failed.map((f) => f.name).join(', '),
          defaultValue: `Could not connect: ${failed.map((f) => f.name).join(', ')}`,
        }),
      );
    }
    setBusy(false);
  }

  function toggleCatalogSelection(githubId: number) {
    setSelectedCatalogIds((prev) =>
      prev.includes(githubId)
        ? prev.filter((id) => id !== githubId)
        : [...prev, githubId],
    );
  }

  async function connectFromUrl() {
    if (!workspaceId || !urlInput.trim()) return;

    // Check OAuth first
    let isConnected = githubConnected;
    if (isConnected === null) {
      try {
        const conn = await githubApi.getConnection();
        isConnected = !!conn && conn.status === 'ACTIVE';
        setGithubConnected(isConnected);
      } catch {
        isConnected = false;
      }
    }

    if (!isConnected) {
      setError(t('repository.githubRequired', 'Please connect GitHub first.'));
      startGithubOAuth();
      return;
    }

    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const meta = await githubApi.getRepositoryByUrl(urlInput.trim());
      const repo = await githubApi.connectRepository(workspaceId, meta.githubId);
      setInfo(t('repository.connected', { name: repo.fullName }));
      setShowConnect(false);
      setUrlInput('');
      await load();
      setSelected(repo);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function syncRepo(repo: Repository) {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const updated = await githubApi.syncRepository(workspaceId, repo.id);
      setInfo(t('repository.syncStarted', { name: updated.fullName }));
      await load();
      if (selected?.id === updated.id) {
        await loadDetails(updated);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function toggleRepoAutoUpdate(repo: Repository) {
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      const updated = await githubApi.setRepositoryAutoUpdate(
        workspaceId,
        repo.id,
        !repo.autoUpdateEnabled,
      );
      setInfo(
        updated.autoUpdateEnabled
          ? t('repository.autoUpdateRepoEnabled', {
              name: updated.fullName,
              defaultValue: `Auto-update enabled for ${updated.fullName}`,
            })
          : t('repository.autoUpdateRepoDisabled', {
              name: updated.fullName,
              defaultValue: `Auto-update disabled for ${updated.fullName}`,
            }),
      );
      await load();
      if (selected?.id === updated.id) setSelected(updated);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function loadModules(repo: Repository) {
    if (!workspaceId) return;
    setModulesLoading(true);
    setModulesError(null);
    try {
      const list = await githubApi.listModules(workspaceId, repo.id);
      setModules(list);
    } catch (err) {
      setModulesError(describeError(err));
      setModules([]);
    } finally {
      setModulesLoading(false);
    }
  }

  async function detectModules(repo: Repository) {
    if (!workspaceId) return;
    setModulesLoading(true);
    setModulesError(null);
    try {
      const detected = await githubApi.detectModules(workspaceId, repo.id);
      if (detected.length === 0) {
        setInfo(
          t('repository.modulesNoneDetected', {
            defaultValue:
              'No modules detected yet. Index the code entities first — modules are derived from the indexed source files.',
          }),
        );
        return;
      }
      // Persist the detected modules so they become part of the
      // auto-update hierarchy instead of a one-off preview.
      const saved = await Promise.all(
        detected.map((m) =>
          githubApi
            .upsertModule(
              workspaceId,
              repo.id,
              m.modulePath,
              m.autoUpdateEnabled,
              m.displayName ?? undefined,
            )
            .catch(() => null),
        ),
      );
      const ok = saved.filter(Boolean) as RepositoryModule[];
      setModules(ok);
      setInfo(
        t('repository.modulesDetected', {
          count: ok.length,
          defaultValue: `Detected ${ok.length} module(s) in ${repo.fullName}.`,
        }),
      );
    } catch (err) {
      setModulesError(describeError(err));
    } finally {
      setModulesLoading(false);
    }
  }

  async function toggleModuleAutoUpdate(module: RepositoryModule) {
    if (!workspaceId || !module.id) return;
    setBusy(true);
    setModulesError(null);
    try {
      const updated = await githubApi.setModuleAutoUpdate(
        workspaceId,
        module.id,
        !module.autoUpdateEnabled,
      );
      setModules((prev) => prev.map((m) => (m.id === updated.id ? updated : m)));
    } catch (err) {
      setModulesError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function removeModule(module: RepositoryModule) {
    if (!workspaceId || !module.id) return;
    setBusy(true);
    try {
      await githubApi.deleteModule(workspaceId, module.id);
      setModules((prev) => prev.filter((m) => m.id !== module.id));
    } catch (err) {
      setModulesError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function disconnectRepo(repo: Repository) {
    if (!workspaceId) return;
    if (!window.confirm(t('repository.disconnectConfirm', { name: repo.fullName }))) return;
    setBusy(true);
    setError(null);
    try {
      await githubApi.disconnectRepository(workspaceId, repo.id);
      setInfo(t('repository.disconnected', { name: repo.fullName }));
      if (selected?.id === repo.id) setSelected(null);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function loadDetails(repo: Repository) {
    if (!workspaceId) return;
    setSelected(repo);
    setPulls([]);
    setCommits([]);
    setEntities([]);
    setEntityDetail(null);
    setEntitySearch('');
    setEntityTypeFilter('ALL');
    setModules([]);
    setModulesError(null);
    setPrsLoading(true);
    setCommitsLoading(true);
    setActiveTab('pulls');
    try {
      const [prs, cs] = await Promise.all([
        githubApi.listPullRequests(workspaceId, repo.id),
        githubApi.listCommits(workspaceId, repo.id, 50),
      ]);
      setPulls(prs);
      setCommits(cs);
    } catch (err) {
      // non-critical
    } finally {
      setPrsLoading(false);
      setCommitsLoading(false);
    }
  }

  async function loadEntities(repo: Repository) {
    if (!workspaceId) return;
    setEntitiesLoading(true);
    try {
      const list = await codeApi.listEntities(workspaceId, repo.id);
      setEntities(list);
    } catch (err) {
      setError(describeError(err));
      setEntities([]);
    } finally {
      setEntitiesLoading(false);
    }
    // Calculate coverage after loading entities
    calculateCoverage();
  }

  function calculateCoverage() {
    const list = entities;
    if (list.length === 0) {
      setCoverageStats(null);
      return;
    }
    const documented = list.filter((entity) => entity.docstring && entity.docstring.trim().length > 0).length;
    setCoverageStats({
      total: list.length,
      documented,
      undocumented: list.length - documented,
    });
  }

  async function handleGenerateAllDocs() {
    if (!workspaceId || !selected) return;
    setGeneratingAll(true);
    setGenerateResult(null);
    try {
      // The backend enqueues a background job and answers 202 — the LLM
      // work (one call per undocumented entity) takes far longer than a
      // single request may block.
      const queued = await aiApi.generateAllForRepository(workspaceId, selected.id);

      if (queued.totalEntities === 0) {
        setGenerateResult('No undocumented code entities found. All entities are already documented.');
        return;
      }

      const final = await pollBulkGeneration(workspaceId, queued.jobId);
      renderBulkResult(final);
    } catch (err) {
      setGenerateResult(describeError(err));
    } finally {
      setGeneratingAll(false);
    }
  }

  /**
   * Follow a bulk generation run until it finishes.
   *
   * <p>Polls every 2 seconds. The run can take a minute or more for a
   * repository with a dozen undocumented entities, so the poll loop is
   * deliberately generous about how long it waits — but it always ends,
   * even if the backend never reports a terminal state.
   */
  async function pollBulkGeneration(wsId: string, jobId: string) {
    const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED']);
    // ~5 minutes worth of polls; a long run should still finish, and a
    // stuck one should not hang the UI forever.
    const maxAttempts = 150;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      const job = await aiApi.bulkGenerationStatus(wsId, jobId);
      if (terminal.has(job.status)) {
        return job;
      }
      setGenerateResult(
        `Generating documentation... ${job.processedEntities}/${job.totalEntities} entities processed`,
      );
    }

    throw new Error('Bulk generation is taking longer than expected. Check back shortly.');
  }

  function renderBulkResult(job: BulkGenerationJobStatus) {
    if (job.status === 'FAILED' && job.generated === 0) {
      setGenerateResult(
        `Generation failed: ${job.errorMessage ?? 'unknown error'}`,
      );
      return;
    }

    let message = `Generated ${job.generated} document(s) for ${selected?.fullName}`;
    if (job.skipped > 0) {
      message += ` (${job.skipped} skipped - already documented)`;
    }
    if (job.failed > 0) {
      message += `, ${job.failed} failed`;
      if (job.errors && job.errors.length > 0) {
        message += `\n${job.errors.slice(0, 3).join(', ')}`;
        if (job.errors.length > 3) {
          message += ` and ${job.errors.length - 3} more`;
        }
      }
    }
    setGenerateResult(message);
    void loadEntities(selected!);
  }

  async function handleScanDriftForRepo() {
    if (!workspaceId || !selected) return;
    setGeneratingAll(true);
    setGenerateResult(null);
    try {
      // Get all documents
      const docs = await documentsApi.list(workspaceId).catch(() => []);
      const docsForRepo = docs.filter((d) => !d.repositoryId || d.repositoryId === selected.id);

      let alerts = 0;
      for (const doc of docsForRepo) {
        try {
          const result = await driftApi.detect(workspaceId, {
            repositoryId: selected.id,
            documentId: doc.id,
            filesBefore: [],
            filesAfter: [],
          });
          alerts += result.ingested;
        } catch {
          // continue
        }
      }
      setGenerateResult(`Drift scan complete. ${alerts} new alert(s) detected.`);
    } catch (err) {
      setGenerateResult(describeError(err));
    } finally {
      setGeneratingAll(false);
    }
  }

  async function handleScanDocs() {
    if (!workspaceId || !selected) return;
    setScanningDocs(true);
    setDocScanError(null);
    setDocScanResult(null);
    try {
      const result = await docSyncApi.scan(workspaceId, selected.id);
      setDocScanResult(result);
    } catch (err: any) {
      // A scan that runs out of time still finished on the server — the
      // proposals it created are in the queue. Say so instead of showing
      // a bare "timeout", which reads like a failure.
      if (err?.code === 'ECONNABORTED') {
        setDocScanError(
          t('repository.scanTimeout', 'The scan took too long and was stopped on this side. ' +
            'It may still have completed on the server — check the update queue, or narrow the scan and try again.'),
        );
      } else {
        setDocScanError(describeError(err));
      }
    } finally {
      setScanningDocs(false);
    }
  }

  function startGithubOAuth() {
    void githubApi
      .getAuthorizeUrl()
      .then(({ url, state }) => {
        // Persist the CSRF state so the callback page can verify it.
        sessionStorage.setItem('github-oauth-state', state);
        window.location.href = url;
      })
      .catch((err) => setError(describeError(err)));
  }

  const filteredCatalog = useMemo(() => {
    if (!catalogSearch.trim()) return catalog;
    const q = catalogSearch.toLowerCase();
    return catalog.filter(
      (c) => c.fullName.toLowerCase().includes(q) || c.name.toLowerCase().includes(q),
    );
  }, [catalog, catalogSearch]);

  // Load entities on demand when the entities tab becomes active
  useEffect(() => {
    if (selected && activeTab === 'entities' && entities.length === 0 && !entitiesLoading) {
      void loadEntities(selected);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, selected?.id]);

  // Load the module auto-update settings when the modules tab is opened.
  useEffect(() => {
    if (selected && activeTab === 'modules' && modules.length === 0 && !modulesLoading) {
      void loadModules(selected);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, selected?.id]);

  // Prefetch entities count when a repository is selected so the tab badge
  // shows the real number instead of "0" until the user clicks the tab.
  // Skip the prefetch if a fetch is already running or already loaded.
  useEffect(() => {
    if (selected && entities.length === 0 && !entitiesLoading) {
      void loadEntities(selected);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id]);
  const availableEntityTypes = useMemo(() => {
    const set = new Set<string>();
    entities.forEach((e) => set.add(e.entityType));
    return Array.from(set).sort();
  }, [entities]);

  const filteredEntities = useMemo(() => {
    return entities.filter((e) => {
      if (entityTypeFilter !== 'ALL' && e.entityType !== entityTypeFilter) return false;
      if (entitySearch.trim()) {
        const q = entitySearch.toLowerCase();
        if (
          !e.qualifiedName.toLowerCase().includes(q) &&
          !e.simpleName.toLowerCase().includes(q) &&
          !e.filePath.toLowerCase().includes(q)
        ) {
          return false;
        }
      }
      return true;
    });
  }, [entities, entitySearch, entityTypeFilter]);

  if (!workspaceId) return null;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <Github className="h-5 w-5" /> {t('repository.title')}
          </h1>
          <p className="text-sm text-muted-foreground">{t('repository.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          {githubConnected === false ? (
            <Button size="sm" onClick={startGithubOAuth}>
              <Github className="mr-1 h-4 w-4" /> {t('repository.connectGithub')}
            </Button>
          ) : (
            <Button size="sm" onClick={openConnect} disabled={busy}>
              <GitBranch className="mr-1 h-4 w-4" /> {t('repository.addRepo')}
            </Button>
          )}
        </div>
      </div>

      {githubConnected === false && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300 flex items-start gap-3">
          <AlertCircle className="h-4 w-4 mt-0.5 shrink-0" />
          <div>
            <div className="font-medium">{t('repository.githubNotConnectedTitle')}</div>
            <p className="mt-1 text-amber-700/80 dark:text-amber-300/80">
              {t('repository.githubNotConnectedDesc')}
            </p>
          </div>
        </div>
      )}

      {error && <ErrorState message={error} />}
      {info && (
        <div className="rounded-md border border-green-500/40 bg-green-500/10 p-3 text-sm text-green-700 dark:text-green-300 flex items-center gap-2">
          <CheckCircle2 className="h-4 w-4" /> {info}
        </div>
      )}

      {/* Filters */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t('repository.searchPlaceholder')}
            className="pl-8"
          />
          {search && (
            <Button
              variant="ghost"
              size="icon"
              className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6"
              onClick={() => setSearch('')}
              aria-label="Clear"
            >
              <X className="h-3 w-3" />
            </Button>
          )}
        </div>
        <div className="flex gap-1 flex-wrap">
          {(['ALL', 'CONNECTED', 'ERROR', 'ARCHIVED', 'DISCONNECTED'] as const).map((s) => (
            <Button
              key={s}
              size="sm"
              variant={statusFilter === s ? 'default' : 'outline'}
              onClick={() => setStatusFilter(s)}
            >
              {t(`repository.filter${s.charAt(0)}${s.slice(1).toLowerCase()}`)}
            </Button>
          ))}
        </div>
      </div>

      {loading ? (
        <LoadingState message={t('repository.loading')} />
      ) : repos.length === 0 ? (
        // No repositories at all - check if GitHub is connected
        githubConnected === false ? (
          // Not connected to GitHub - show prominent CTA
          <div className="flex flex-col items-center justify-center py-16 px-4 text-center">
            <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center mb-4">
              <Github className="h-8 w-8 text-muted-foreground" />
            </div>
            <h3 className="text-lg font-semibold mb-2">{t('repository.connectGithubFirstTitle', 'Connect GitHub to get started')}</h3>
            <p className="text-sm text-muted-foreground mb-6 max-w-md">
              {t('repository.connectGithubFirstDesc', 'You need to connect your GitHub account before you can add repositories to this workspace.')}
            </p>
            <Button size="lg" onClick={startGithubOAuth}>
              <Github className="mr-2 h-5 w-5" /> {t('repository.connectGithub', 'Connect GitHub')}
            </Button>
          </div>
        ) : (
          // Connected but no repos yet
          <EmptyState
            icon={<Github className="h-8 w-8" />}
            title={t('repository.emptyTitle')}
            description={t('repository.emptyDesc')}
            action={
              <Button onClick={openConnect}>
                <GitBranch className="mr-1 h-4 w-4" /> {t('repository.addRepo')}
              </Button>
            }
          />
        )
      ) : filteredRepos.length === 0 ? (
        // Has repos but no matches for filter
        <EmptyState
          icon={<Github className="h-8 w-8" />}
          title={t('repository.noMatchTitle')}
          description={t('repository.noMatchDesc')}
          action={null}
        />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {filteredRepos.map((r) => (
            <Card
              key={r.id}
              className={`group transition-shadow hover:shadow-md cursor-pointer ${
                selected?.id === r.id ? 'ring-2 ring-primary' : ''
              }`}
              onClick={() => loadDetails(r)}
            >
              <CardHeader>
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-base flex items-center gap-2 truncate">
                    <Github className="h-4 w-4 shrink-0" />
                    <span className="truncate">{r.fullName}</span>
                  </CardTitle>
                  <StatusBadge status={r.status} />
                </div>
                {r.description && (
                  <CardDescription className="line-clamp-2">{r.description}</CardDescription>
                )}
              </CardHeader>
              <CardContent className="space-y-2 text-xs text-muted-foreground">
                <div className="flex items-center gap-3 flex-wrap">
                  <span className="flex items-center gap-1">
                    <GitBranch className="h-3 w-3" /> {r.defaultBranch}
                  </span>
                  {r.isPrivate && <Badge variant="muted" className="text-[10px]">private</Badge>}
                  {r.htmlUrl && (
                    <a
                      href={r.htmlUrl}
                      target="_blank"
                      rel="noreferrer"
                      onClick={(e) => e.stopPropagation()}
                      className="flex items-center gap-1 hover:text-primary"
                    >
                      <ExternalLink className="h-3 w-3" /> {t('repository.openOnGithub')}
                    </a>
                  )}
                </div>
                <div>
                  {t('repository.lastSynced', {
                    when: r.lastSyncedAt
                      ? new Date(r.lastSyncedAt).toLocaleString()
                      : t('repository.never'),
                  })}
                </div>
                <div className="flex gap-1 pt-2" onClick={(e) => e.stopPropagation()}>
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-7 text-xs"
                    onClick={() => syncRepo(r)}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.sync')}
                  </Button>
                  {canManageAutoUpdate && (
                    <Button
                      size="sm"
                      variant={r.autoUpdateEnabled ? 'ghost' : 'outline'}
                      className="h-7 text-xs"
                      onClick={() => toggleRepoAutoUpdate(r)}
                      disabled={busy}
                      title={t('repository.autoUpdateRepoTitle', 'Turn AI auto-update on or off for this repository.')}
                    >
                      <RefreshCw className="mr-1 h-3 w-3" />
                      {r.autoUpdateEnabled
                        ? t('repository.autoUpdateOn', 'Auto-update ON')
                        : t('repository.autoUpdateOff', 'Auto-update OFF')}
                    </Button>
                  )}
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 text-xs text-destructive"
                    onClick={() => disconnectRepo(r)}
                    disabled={busy}
                  >
                    <Unplug className="mr-1 h-3 w-3" /> {t('repository.disconnect')}
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Coverage Stats Panel */}
      {selected && (
        <div className="grid gap-4 md:grid-cols-3">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm flex items-center gap-2">
                <Sparkles className="h-4 w-4" /> {t('repository.coverageTitle')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              {coverageStats ? (
                <div className="space-y-3">
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">{t('repository.coverageTotalEntities')}</span>
                    <span className="font-medium">{coverageStats.total}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">{t('repository.coverageDocumented')}</span>
                    <span className="font-medium text-green-600">{coverageStats.documented}</span>
                  </div>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">{t('repository.coverageUndocumented')}</span>
                    <span className="font-medium text-amber-600">{coverageStats.undocumented}</span>
                  </div>
                  <div className="pt-2">
                    <div className="h-2 bg-muted rounded-full overflow-hidden">
                      <div
                        className="h-full bg-green-500 transition-all"
                        style={{ width: `${coverageStats.total > 0 ? (coverageStats.documented / coverageStats.total) * 100 : 0}%` }}
                      />
                    </div>
                    <p className="text-xs text-muted-foreground mt-1">
                      {t('repository.coveragePercent', {
                        percent: coverageStats.total > 0
                          ? Math.round((coverageStats.documented / coverageStats.total) * 100)
                          : 0,
                      })}
                    </p>
                  </div>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  {entities.length > 0
                    ? t('repository.coverageCalculating')
                    : t('repository.coverageNoEntities')}
                </p>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm flex items-center gap-2">
                <Play className="h-4 w-4" /> {t('repository.quickActions')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <Button
                size="sm"
                variant="outline"
                className="w-full justify-start"
                onClick={() => void handleGenerateAllDocs()}
                disabled={generatingAll}
              >
                <Sparkles className="mr-2 h-4 w-4" />
                {generatingAll ? t('repository.actionProcessing') : t('repository.actionGenerateAll')}
              </Button>
              <Button
                size="sm"
                variant="outline"
                className="w-full justify-start"
                onClick={() => void handleScanDriftForRepo()}
                disabled={generatingAll}
              >
                <Shield className="mr-2 h-4 w-4" />
                {generatingAll ? t('repository.actionScanning') : t('repository.actionScanDrift')}
              </Button>
              <Button
                size="sm"
                variant="outline"
                className="w-full justify-start"
                onClick={() => void handleScanDocs()}
                disabled={scanningDocs}
              >
                <Loader2 className={`mr-2 h-4 w-4 ${scanningDocs ? 'animate-spin' : ''}`} />
                {scanningDocs ? t('repository.actionScanningDocs') : t('repository.actionSyncDocs')}
              </Button>
              {generateResult && (
                <div className={`text-xs p-2 rounded ${generateResult.includes('complete') || generateResult.includes('Generated') ? 'bg-green-500/10 text-green-700' : 'bg-destructive/10 text-destructive'}`}>
                  {generateResult}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Doc Scan Results */}
          {docScanResult && (
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center gap-2">
                  <BookOpen className="h-4 w-4" /> {t('repository.docSyncResults')}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="grid grid-cols-4 gap-2 text-center">
                  <div className="bg-muted/50 rounded p-2">
                    <div className="text-lg font-bold">{docScanResult.totalFiles}</div>
                    <div className="text-xs text-muted-foreground">{t('repository.docSyncFiles')}</div>
                  </div>
                  <div className="bg-muted/50 rounded p-2">
                    <div className="text-lg font-bold">{docScanResult.referencedEntities}</div>
                    <div className="text-xs text-muted-foreground">{t('repository.docSyncReferences')}</div>
                  </div>
                  <div className="bg-muted/50 rounded p-2">
                    <div className="text-lg font-bold">{docScanResult.updatedEntities}</div>
                    <div className="text-xs text-muted-foreground">{t('repository.docSyncMatched')}</div>
                  </div>
                  <div className={`rounded p-2 ${docScanResult.driftCount > 0 ? 'bg-amber-500/20 text-amber-700' : 'bg-muted/50'}`}>
                    <div className="text-lg font-bold">{docScanResult.driftCount}</div>
                    <div className="text-xs">{t('repository.docSyncNeedUpdate')}</div>
                  </div>
                </div>
                {docScanResult.driftCount > 0 && (
                  <div className="bg-amber-500/10 border border-amber-500/30 rounded p-2 text-xs text-amber-700 flex items-center gap-2">
                    <AlertTriangle className="h-3 w-3 flex-shrink-0" />
                    <span>
                      <b>{docScanResult.driftCount}</b>{' '}
                      {t('repository.docSyncFilesNeedUpdate')}
                    </span>
                  </div>
                )}
                {(docScanResult.queuedCount ?? 0) > 0 && (
                  <div className="bg-green-500/10 border border-green-500/30 rounded p-2 text-xs text-green-700 flex items-center gap-2">
                    <CheckCircle2 className="h-3 w-3 flex-shrink-0" />
                    <span>
                      <b>{docScanResult.queuedCount}</b>{' '}
                      {t('repository.docSyncQueued')}
                      {docScanResult.skippedCount
                        ? ` ${t('repository.docSyncSkipped', { count: docScanResult.skippedCount })}`
                        : ''}
                      .{' '}
                      <Link
                        to={`/workspaces/${workspaceId}/doc-updates`}
                        className="underline font-medium"
                      >
                        {t('repository.viewQueue')}
                      </Link>
                    </span>
                  </div>
                )}
                {docScanResult.files.length > 0 && (
                  <div className="max-h-48 overflow-y-auto space-y-1">
                    {docScanResult.files.slice(0, 10).map((file) => (
                      <div
                        key={file.path}
                        className={`text-xs p-2 rounded flex flex-col gap-1 ${
                          file.hasDrift
                            ? 'bg-amber-500/10 border border-amber-500/30'
                            : 'bg-muted/30'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2 min-w-0 flex-1">
                            <BookOpen className="h-3 w-3 flex-shrink-0" />
                            <span className="truncate font-mono">{file.path}</span>
                          </div>
                          <div className="flex items-center gap-1 flex-shrink-0">
                            <Badge variant="outline" className="text-xs">
                              {t('repository.refsCount', { count: file.foundEntities.length })}
                            </Badge>
                            <Button
                              size="sm"
                              variant={file.hasDrift ? 'default' : 'ghost'}
                              className="h-6 px-2 text-xs"
                              onClick={() => setPreviewingFile(file.path)}
                            >
                              {t('repository.reviewAndUpdate')}
                            </Button>
                          </div>
                        </div>
                        {file.hasDrift && file.driftReason && (
                          <div className="text-amber-700 text-[10px] pl-5">
                            {file.driftReason}
                          </div>
                        )}
                      </div>
                    ))}
                    {docScanResult.files.length > 10 && (
                      <p className="text-xs text-muted-foreground text-center">
                        {t('repository.moreFiles', { count: docScanResult.files.length - 10 })}
                      </p>
                    )}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {docScanError && (
            <div className="text-xs p-2 rounded bg-destructive/10 text-destructive">
              {docScanError}
            </div>
          )}

          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm flex items-center gap-2">
                <BookOpen className="h-4 w-4" /> {t('repository.repoInfo')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t('repository.infoStatus')}</span>
                <Badge variant={selected.status === 'CONNECTED' ? 'success' : 'muted'}>{selected.status}</Badge>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t('repository.infoBranch')}</span>
                <span className="font-mono text-xs">{selected.defaultBranch}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t('repository.infoLastSynced')}</span>
                <span className="text-xs">
                  {selected.lastSyncedAt
                    ? new Date(selected.lastSyncedAt).toLocaleDateString()
                    : t('repository.never')}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">{t('repository.infoVisibility')}</span>
                <Badge variant={selected.isPrivate ? 'destructive' : 'success'} className="text-xs">
                  {selected.isPrivate
                    ? t('repository.visibilityPrivate')
                    : t('repository.visibilityPublic')}
                </Badge>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Detail panel: PRs and commits for selected repo */}
      {selected && (
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base flex items-center gap-2">
                <GitPullRequest className="h-4 w-4" /> {selected.fullName}
              </CardTitle>
              <div className="flex items-center gap-2">
                <div className="flex gap-1 border rounded-md p-0.5">
                  <button
                    onClick={() => setActiveTab('pulls')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'pulls' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <GitPullRequest className="h-3 w-3" />
                    {t('repository.tabPRs', 'Pull Requests')} ({pulls.length})
                  </button>
                  <button
                    onClick={() => setActiveTab('commits')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'commits' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <GitCommit className="h-3 w-3" />
                    {t('repository.tabCommits', 'Commits')} ({commits.length})
                  </button>
                  <button
                    onClick={() => setActiveTab('entities')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'entities' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <Code2 className="h-3 w-3" />
                    {t('repository.tabEntities', 'Code Entities')} ({entities.length})
                  </button>
                  <button
                    onClick={() => setActiveTab('modules')}
                    className={`flex items-center gap-1 px-3 py-1 rounded text-xs font-medium transition-colors ${
                      activeTab === 'modules' ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'
                    }`}
                  >
                    <Boxes className="h-3 w-3" />
                    {t('repository.tabModules', 'Modules')} ({modules.length})
                  </button>
                </div>
                {activeTab === 'pulls' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => githubApi
                      .ingestPullRequests(workspaceId, selected.id, 'all')
                      .then(() => loadDetails(selected))
                      .catch((err) => setError(describeError(err)))}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.ingestAll')}
                  </Button>
                )}
                {activeTab === 'commits' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={async () => {
                      try {
                        setBusy(true);
                        const result = await githubApi.ingestCommits(workspaceId, selected.id, 50);
                        setInfo(t('repository.commitsIngested', {
                          count: result.ingested,
                          name: selected.fullName,
                        }));
                        await loadDetails(selected);
                      } catch (err) {
                        setError(describeError(err));
                      } finally {
                        setBusy(false);
                      }
                    }}
                    disabled={busy}
                  >
                    <RefreshCw className="mr-1 h-3 w-3" /> {t('repository.fetchFromGithub', 'Fetch from GitHub')}
                  </Button>
                )}
                {activeTab === 'modules' && (
                  <div className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void loadModules(selected)}
                      disabled={modulesLoading}
                    >
                      <RefreshCw className={`mr-1 h-3 w-3 ${modulesLoading ? 'animate-spin' : ''}`} />
                      {t('repository.modulesRefresh', 'Refresh')}
                    </Button>
                    {canManageAutoUpdate && (
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void detectModules(selected)}
                        disabled={modulesLoading}
                      >
                        <Boxes className="mr-1 h-3 w-3" />
                        {t('repository.modulesDetect', 'Detect modules')}
                      </Button>
                    )}
                  </div>
                )}
                {activeTab === 'entities' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={async () => {
                      try {
                        setBusy(true);
                        const result = await githubApi.ingestCode(workspaceId, selected.id);
                        setInfo(`Indexed ${result.ingested} code entities from ${selected.fullName}`);
                        await loadEntities(selected);
                      } catch (err) {
                        setError(describeError(err));
                      } finally {
                        setBusy(false);
                      }
                    }}
                    disabled={busy}
                  >
                    <Code2 className="mr-1 h-3 w-3" />
                    {busy ? 'Indexing…' : 'Ingest Code'}
                  </Button>
                )}
              </div>
            </div>
          </CardHeader>
          <CardContent className="p-0">

            {/* Pull Requests Tab */}
            {activeTab === 'pulls' && (
              <>
                {prsLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingPRs')}
                  </div>
                ) : pulls.length === 0 ? (
                  <p className="text-sm text-muted-foreground px-4 py-6">
                    {t('repository.noPRs')}
                  </p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>#</TableHead>
                        <TableHead>{t('repository.colPRTitle')}</TableHead>
                        <TableHead>{t('repository.colPRState')}</TableHead>
                        <TableHead>{t('repository.colPRAuthor')}</TableHead>
                        <TableHead>{t('repository.colPRBranch')}</TableHead>
                        <TableHead className="text-right">{t('repository.colPRUpdated')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {pulls.map((p) => (
                        <TableRow key={p.id}>
                          <TableCell className="font-mono text-xs">#{p.number}</TableCell>
                          <TableCell>
                            {p.htmlUrl ? (
                              <a
                                href={p.htmlUrl}
                                target="_blank"
                                rel="noreferrer"
                                className="hover:text-primary flex items-center gap-1"
                              >
                                {p.title}
                                <ExternalLink className="h-3 w-3" />
                              </a>
                            ) : (
                              p.title
                            )}
                            {p.isDraft && (
                              <Badge variant="muted" className="ml-2 text-[10px]">draft</Badge>
                            )}
                          </TableCell>
                          <TableCell>
                            <PRStateBadge state={p.state} />
                          </TableCell>
                          <TableCell className="text-xs">{p.authorLogin || '—'}</TableCell>
                          <TableCell className="font-mono text-xs">
                            {p.headBranch} → {p.baseBranch}
                          </TableCell>
                          <TableCell className="text-right text-xs text-muted-foreground">
                            {new Date(p.updatedAt).toLocaleDateString()}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </>
            )}

            {/* Code Entities Tab */}
            {activeTab === 'entities' && (
              <>
                <div className="flex flex-col gap-2 sm:flex-row sm:items-center p-4 border-b">
                  <div className="relative flex-1">
                    <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      value={entitySearch}
                      onChange={(e) => setEntitySearch(e.target.value)}
                      placeholder={t('repository.entitySearchPlaceholder', 'Search by name or file path…')}
                      className="pl-8"
                    />
                    {entitySearch && (
                      <Button
                        variant="ghost"
                        size="icon"
                        className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6"
                        onClick={() => setEntitySearch('')}
                        aria-label="Clear"
                      >
                        <X className="h-3 w-3" />
                      </Button>
                    )}
                  </div>
                  <select
                    value={entityTypeFilter}
                    onChange={(e) => setEntityTypeFilter(e.target.value)}
                    className="h-9 rounded-md border border-input bg-background px-3 text-xs"
                  >
                    <option value="ALL">{t('repository.entityTypeAll', 'All types')}</option>
                    {availableEntityTypes.map((t) => (
                      <option key={t} value={t}>{t}</option>
                    ))}
                  </select>
                </div>
                {entitiesLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingEntities', 'Loading code entities…')}
                  </div>
                ) : entities.length === 0 ? (
                  <div className="text-center py-12 px-4">
                    <Code2 className="h-10 w-10 text-muted-foreground mx-auto mb-3" />
                    <p className="text-sm font-medium mb-1">
                      {t('repository.noEntitiesTitle', 'No code entities yet')}
                    </p>
                    <p className="text-xs text-muted-foreground max-w-md mx-auto">
                      {t('repository.noEntitiesDesc', 'Code entities (classes, methods, functions) are extracted automatically when the repository is ingested. Use the sync button on the repository card, or trigger a manual sync from GitHub.')}
                    </p>
                  </div>
                ) : filteredEntities.length === 0 ? (
                  <p className="text-sm text-muted-foreground px-4 py-6">
                    {t('repository.noEntityMatch', 'No entities match your filter.')}
                  </p>
                ) : (
                  <>
                    <div className="px-4 py-2 text-xs text-muted-foreground border-b bg-muted/30">
                      {t('repository.entitySummary', {
                        shown: filteredEntities.length,
                        total: entities.length,
                        defaultValue: `Showing ${filteredEntities.length} of ${entities.length} entities`,
                      })}
                    </div>
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>{t('repository.colEntityName', 'Name')}</TableHead>
                          <TableHead>{t('repository.colEntityType', 'Type')}</TableHead>
                          <TableHead>{t('repository.colEntityFile', 'File')}</TableHead>
                          <TableHead>{t('repository.colEntityLanguage', 'Lang')}</TableHead>
                          <TableHead className="text-right">{t('repository.colEntityCommit', 'Commit')}</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredEntities.map((e) => (
                          <TableRow
                            key={e.id}
                            className="cursor-pointer hover:bg-muted/30"
                            onClick={() => setEntityDetail(e)}
                          >
                            <TableCell>
                              <div className="font-mono text-xs text-blue-600 dark:text-blue-400 truncate max-w-[260px]" title={e.qualifiedName}>
                                {e.simpleName || e.qualifiedName}
                              </div>
                              {e.simpleName && e.simpleName !== e.qualifiedName && (
                                <div className="text-[10px] text-muted-foreground font-mono truncate max-w-[260px]" title={e.qualifiedName}>
                                  {e.qualifiedName}
                                </div>
                              )}
                            </TableCell>
                            <TableCell>
                              <Badge variant="muted" className="text-[10px]">
                                {e.entityType}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-xs text-muted-foreground font-mono max-w-[280px]">
                              <div className="truncate" title={e.filePath}>{e.filePath}</div>
                              <div className="text-[10px]">
                                L{e.startLine}-{e.endLine}
                              </div>
                            </TableCell>
                            <TableCell>
                              <Badge variant="outline" className="text-[10px] uppercase">
                                {e.language}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right font-mono text-[10px] text-muted-foreground">
                              {e.commitSha?.substring(0, 7)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </>
                )}
              </>
            )}

            {/* Modules Tab — per-module AI auto-update hierarchy */}
            {activeTab === 'modules' && (
              <>
                <div className="flex items-center justify-between gap-2 p-4 border-b">
                  <p className="text-xs text-muted-foreground">
                    {t('repository.modulesHint', {
                      defaultValue:
                        'A module with auto-update off keeps its documents untouched by AI updates, even when the repository is auto-updating.',
                    })}
                  </p>
                  {modulesError && (
                    <span className="text-xs text-destructive">{modulesError}</span>
                  )}
                </div>
                {modulesLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    {t('repository.modulesLoading', 'Loading modules…')}
                  </div>
                ) : modules.length === 0 ? (
                  <div className="text-center py-12 px-4">
                    <Boxes className="h-10 w-10 text-muted-foreground mx-auto mb-3" />
                    <p className="text-sm font-medium mb-1">
                      {t('repository.modulesEmptyTitle', 'No modules configured')}
                    </p>
                    <p className="text-xs text-muted-foreground max-w-md mx-auto">
                      {t('repository.modulesEmptyDesc', {
                        defaultValue:
                          'Use "Detect modules" to derive the package directories of this repository from its indexed code entities, then exclude the ones that should not be auto-updated.',
                      })}
                    </p>
                  </div>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('repository.colModulePath', 'Module path')}</TableHead>
                        <TableHead>{t('repository.colModuleName', 'Display name')}</TableHead>
                        <TableHead>{t('repository.colModuleAutoUpdate', 'Auto-update')}</TableHead>
                        {canManageAutoUpdate && (
                          <TableHead className="text-right">
                            {t('repository.colActions', 'Actions')}
                          </TableHead>
                        )}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {modules.map((m) => (
                        <TableRow key={m.id ?? m.modulePath}>
                          <TableCell className="font-mono text-xs">{m.modulePath}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {m.displayName || '—'}
                          </TableCell>
                          <TableCell>
                            {canManageAutoUpdate ? (
                              <Button
                                size="sm"
                                variant={m.autoUpdateEnabled ? 'ghost' : 'outline'}
                                className="h-7 text-xs"
                                onClick={() => void toggleModuleAutoUpdate(m)}
                                disabled={busy || !m.id}
                              >
                                <RefreshCw className="mr-1 h-3 w-3" />
                                {m.autoUpdateEnabled
                                  ? t('repository.autoUpdateOn', 'Auto-update ON')
                                  : t('repository.autoUpdateOff', 'Auto-update OFF')}
                              </Button>
                            ) : (
                              <Badge variant={m.autoUpdateEnabled ? 'success' : 'muted'} className="text-[10px]">
                                {m.autoUpdateEnabled ? 'ON' : 'OFF'}
                              </Badge>
                            )}
                          </TableCell>
                          {canManageAutoUpdate && (
                            <TableCell className="text-right">
                              <Button
                                size="sm"
                                variant="ghost"
                                className="h-7 text-xs text-destructive"
                                onClick={() => void removeModule(m)}
                                disabled={busy || !m.id}
                              >
                                {t('common.remove', 'Remove')}
                              </Button>
                            </TableCell>
                          )}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </>
            )}

            {/* Commits Tab */}
            {activeTab === 'commits' && (
              <>
                {commitsLoading ? (
                  <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingCommits', 'Loading commits...')}
                  </div>
                ) : commits.length === 0 ? (
                  <div className="text-center py-8 px-4">
                    <GitCommit className="h-8 w-8 text-muted-foreground mx-auto mb-3" />
                    <p className="text-sm text-muted-foreground mb-2">
                      {t('repository.noCommits', 'No commits found')}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {t('repository.noCommitsHint', 'Click "Fetch from GitHub" to load commits from this repository.')}
                    </p>
                  </div>
                ) : (
                  <div className="divide-y">
                    {commits.map((c) => (
                      <div key={c.id} className="px-4 py-3 hover:bg-muted/20 transition-colors">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center gap-2 flex-wrap">
                              {/* SHA */}
                              {c.url ? (
                                <a
                                  href={c.url}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="font-mono text-xs text-primary hover:underline"
                                >
                                  {c.shortSha || c.sha?.substring(0, 7)}
                                </a>
                              ) : (
                                <span className="font-mono text-xs text-muted-foreground">
                                  {c.shortSha || c.sha?.substring(0, 7)}
                                </span>
                              )}
                              {/* Branch */}
                              {c.branch && (
                                <Badge variant="muted" className="text-[10px] flex items-center gap-0.5">
                                  <GitBranch className="h-2.5 w-2.5" />
                                  {c.branch}
                                </Badge>
                              )}
                              {/* Author */}
                              {c.authorName && (
                                <span className="text-xs text-muted-foreground">
                                  {c.authorName}
                                </span>
                              )}
                            </div>
                            {/* Message */}
                            <p className="text-sm mt-1 line-clamp-2">
                              {c.message}
                            </p>
                            {/* Meta */}
                            <div className="flex items-center gap-3 mt-1 text-xs text-muted-foreground">
                              {c.authorEmail && (
                                <span>{c.authorEmail}</span>
                              )}
                              <span>
                                {c.authoredAt ? new Date(c.authoredAt).toLocaleString() : ''}
                              </span>
                              {c.url && (
                                <a
                                  href={c.url}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="flex items-center gap-1 hover:text-primary"
                                >
                                  <ExternalLink className="h-3 w-3" />
                                  GitHub
                                </a>
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      {/* Connect dialog */}
      <Dialog open={showConnect} onOpenChange={setShowConnect}>
        <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('repository.addRepo')}</DialogTitle>
            <DialogDescription>{t('repository.addRepoDesc')}</DialogDescription>
          </DialogHeader>

          <div className="flex gap-2 border-b">
            <Button
              variant={tab === 'catalog' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setTab('catalog')}
              className="rounded-b-none"
            >
              <Github className="mr-1 h-4 w-4" /> {t('repository.tabCatalog')}
            </Button>
            <Button
              variant={tab === 'url' ? 'default' : 'ghost'}
              size="sm"
              onClick={() => setTab('url')}
              className="rounded-b-none"
            >
              <GitBranch className="mr-1 h-4 w-4" /> {t('repository.tabUrl')}
            </Button>
          </div>

          {tab === 'catalog' ? (
            <div className="space-y-3 pt-2">
              <Input
                value={catalogSearch}
                onChange={(e) => setCatalogSearch(e.target.value)}
                placeholder={t('repository.searchCatalogPlaceholder')}
              />
              {selectedCatalogIds.length > 0 && (
                <div className="flex items-center justify-between gap-2 rounded-md border border-primary/40 bg-primary/5 px-3 py-2">
                  <span className="text-xs">
                    {t('repository.selectedCount', { count: selectedCatalogIds.length })}
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      className="h-7 text-xs"
                      onClick={() => setSelectedCatalogIds([])}
                    >
                      {t('repository.clearSelection', 'Clear')}
                    </Button>
                    <Button
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => void connectSelectedFromCatalog()}
                      disabled={busy}
                    >
                      {busy && <Loader2 className="mr-1 h-3 w-3 animate-spin" />}
                      {t('repository.connectSelected', 'Connect selected')}
                    </Button>
                  </div>
                </div>
              )}
              {catalogLoading ? (
                <div className="flex items-center gap-2 p-4 text-sm text-muted-foreground">
                  <Loader2 className="h-4 w-4 animate-spin" /> {t('repository.loadingCatalog')}
                </div>
              ) : catalogErr ? (
                // Check if it's an OAuth error - show friendly prompt instead of raw error
                catalogErr.includes('No GitHub connection') || catalogErr.includes('github') ? (
                  <div className="flex flex-col items-center justify-center py-8 text-center">
                    <Github className="h-10 w-10 text-muted-foreground mb-3" />
                    <p className="text-sm font-medium mb-1">{t('repository.githubRequired', 'GitHub connection required')}</p>
                    <p className="text-xs text-muted-foreground mb-4">
                      {t('repository.githubRequiredDesc', 'Connect your GitHub account to browse repositories.')}
                    </p>
                    <Button size="sm" onClick={startGithubOAuth}>
                      <Github className="mr-1 h-4 w-4" /> {t('repository.connectGithub')}
                    </Button>
                  </div>
                ) : (
                  <ErrorState message={catalogErr} />
                )
              ) : filteredCatalog.length === 0 ? (
                <p className="text-sm text-muted-foreground p-3">
                  {t('repository.emptyCatalog')}
                </p>
              ) : (
                <div className="space-y-1 max-h-96 overflow-y-auto">
                  {filteredCatalog.map((c) => {
                    const ticked = selectedCatalogIds.includes(c.githubId);
                    return (
                      <div
                        key={c.githubId}
                        className={`flex items-center justify-between rounded-md border px-3 py-2 ${
                          ticked ? 'bg-primary/5 border-primary/40' : 'hover:bg-muted/30'
                        }`}
                      >
                        <div className="flex items-center gap-3 min-w-0 flex-1">
                          <input
                            type="checkbox"
                            checked={ticked}
                            onChange={() => toggleCatalogSelection(c.githubId)}
                            className="h-4 w-4 shrink-0 rounded border-input"
                            aria-label={c.fullName}
                          />
                          <div className="min-w-0 flex-1">
                            <div className="text-sm font-medium truncate">{c.fullName}</div>
                            <div className="text-xs text-muted-foreground flex items-center gap-2">
                              <GitBranch className="h-3 w-3" />
                              {c.defaultBranch}
                              {c.isPrivate && <Badge variant="muted" className="text-[10px]">private</Badge>}
                              {c.isArchived && <Badge variant="muted" className="text-[10px]">archived</Badge>}
                            </div>
                            {c.description && (
                              <div className="text-xs text-muted-foreground line-clamp-1">
                                {c.description}
                              </div>
                            )}
                          </div>
                        </div>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => connectFromCatalog(c.githubId)}
                          disabled={busy}
                        >
                          {t('repository.connect')}
                        </Button>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          ) : (
            <form onSubmit={(e: FormEvent) => { e.preventDefault(); void connectFromUrl(); }} className="space-y-3 pt-2">
              <div className="space-y-1.5">
                <Label htmlFor="repo-url">{t('repository.urlLabel')}</Label>
                <Input
                  id="repo-url"
                  type="url"
                  value={urlInput}
                  onChange={(e) => setUrlInput(e.target.value)}
                  placeholder="https://github.com/owner/repo"
                  required
                />
                <p className="text-xs text-muted-foreground">{t('repository.urlHint')}</p>
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={() => setShowConnect(false)}>
                  {t('common.cancel')}
                </Button>
                <Button type="submit" disabled={busy || !urlInput.trim()}>
                  {busy ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <GitBranch className="mr-1 h-4 w-4" />}
                  {t('repository.connect')}
                </Button>
              </DialogFooter>
            </form>
          )}

          <DialogFooter>
            <Button variant="ghost" size="sm" asChild>
              <Link to={`/workspaces/${workspaceId}/integrations`}>
                <Sparkles className="mr-1 h-3 w-3" /> {t('repository.manageConnections')}
              </Link>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Entity detail dialog */}
      <Dialog open={!!entityDetail} onOpenChange={(o) => { if (!o) setEntityDetail(null); }}>
        <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Code2 className="h-4 w-4" />
              {entityDetail?.simpleName || entityDetail?.qualifiedName}
            </DialogTitle>
            <DialogDescription>
              <span className="font-mono text-xs">{entityDetail?.qualifiedName}</span>
            </DialogDescription>
          </DialogHeader>
          {entityDetail && (
            <div className="space-y-3 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="muted">{entityDetail.entityType}</Badge>
                <Badge variant="outline" className="uppercase text-[10px]">{entityDetail.language}</Badge>
                <code className="text-xs text-muted-foreground font-mono">
                  {entityDetail.filePath}:{entityDetail.startLine}-{entityDetail.endLine}
                </code>
              </div>
              {entityDetail.signature && (
                <div>
                  <div className="text-xs font-semibold text-muted-foreground mb-1">{t('repository.entitySignature')}</div>
                  <pre className="text-xs font-mono whitespace-pre-wrap bg-muted/40 rounded p-2 break-words">
                    {entityDetail.signature}
                  </pre>
                </div>
              )}
              {entityDetail.docstring && (
                <div>
                  <div className="text-xs font-semibold text-muted-foreground mb-1">{t('repository.entityDocstring')}</div>
                  <pre className="text-xs whitespace-pre-wrap bg-muted/30 rounded p-2">
                    {entityDetail.docstring}
                  </pre>
                </div>
              )}
              {entityDetail.metadata && (
                <div>
                  <div className="text-xs font-semibold text-muted-foreground mb-1">{t('repository.entityMetadata')}</div>
                  <pre className="text-[10px] font-mono whitespace-pre-wrap bg-muted/30 rounded p-2 break-words">
                    {entityDetail.metadata}
                  </pre>
                </div>
              )}
              <div className="text-xs text-muted-foreground">
                {t('repository.entityIngestedAt', {
                  when: new Date(entityDetail.ingestedAt).toLocaleString(),
                })}
              </div>
              {/* The impact analysis: what a change to this entity
                  would invalidate. The backend enforces the same
                  capability, so the section is simply hidden for a
                  reader who may not use it. */}
              {canViewImpact && (
                <div className="border-t pt-3">
                  <ImpactPanel
                    workspaceId={workspaceId!}
                    entityId={entityDetail.id}
                  />
                </div>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setEntityDetail(null)}>
              {t('common.close', 'Close')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Doc Sync Preview Modal */}
      {previewingFile && selected && (
        <DocSyncPreviewModal
          workspaceId={workspaceId}
          repositoryId={selected.id}
          filePath={previewingFile}
          onClose={() => setPreviewingFile(null)}
          onApplied={(url) => {
            setPrUrl(url);
            setPreviewingFile(null);
            // Clear doc scan result after successful apply
            setDocScanResult(null);
            if (url) {
              setGenerateResult(`Pull request created: ${url}`);
              // Auto-clear the result after 10 seconds
              setTimeout(() => {
                setGenerateResult(null);
              }, 10000);
            }
          }}
        />
      )}

      {/* PR Created Success Dialog */}
      {prUrl && (
        <Dialog open={!!prUrl} onOpenChange={() => setPrUrl(null)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="w-5 h-5 text-green-500" />
                Pull Request Created!
              </DialogTitle>
              <DialogDescription>
                Your documentation changes have been submitted for review.
              </DialogDescription>
            </DialogHeader>
            <div className="py-4">
              <a
                href={prUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 text-blue-600 hover:underline"
              >
                <GitPullRequest className="w-4 h-4" />
                {prUrl}
              </a>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setPrUrl(null)}>
                Close
              </Button>
              <Button asChild>
                <a
                  href={prUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-2"
                >
                  <ExternalLink className="w-4 h-4" />
                  View on GitHub
                </a>
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: Repository['status'] }) {
  const variant =
    status === 'CONNECTED'
      ? 'success'
      : status === 'ERROR'
      ? 'destructive'
      : status === 'ARCHIVED'
      ? 'muted'
      : 'muted';
  return <Badge variant={variant} className="text-[10px]">{status}</Badge>;
}

function PRStateBadge({ state }: { state: PullRequest['state'] }) {
  const variant =
    state === 'OPEN' ? 'info' : state === 'MERGED' ? 'success' : 'muted';
  return <Badge variant={variant} className="text-[10px]">{state}</Badge>;
}
