import RegenerateDocButton from '../components/RegenerateDocButton';
import { tagsApi, DocumentTag } from '../services/tags';
import { DocumentTags } from '../components/DocumentTags';
import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, AlertCircle, Bot, Check, Eye, ExternalLink, Github, History, Link2, Loader2, Plus, RotateCcw, Shield, Sparkles, Upload, X, Play } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../components/ui/tabs';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { documentsApi, Document, DocumentVersion, DocumentVersionSummary, ActorRole, VersionStatus } from '../services/documents';
import { driftApi, DriftAlert } from '../services/drift';
import { linksApi, CodeDocumentLink } from '../services/knowledge';
import { codeApi, CodeEntity } from '../services/knowledge';
import { aiApi } from '../services/ai';
import { githubApi, Repository } from '../services/github';
import { describeError } from '../services/auth';
import { templatesApi, DocTemplate } from '../services/templates';
import { format } from 'date-fns';
const STATUS_VARIANT: Record<VersionStatus, 'muted' | 'warning' | 'success' | 'info' | 'destructive' | 'secondary'> = {
  PENDING: 'warning',
  IN_REVIEW: 'info',
  APPROVED: 'success',
  PUBLISHED: 'info',
  REJECTED: 'destructive',
  SUPERSEDED: 'muted',
};

const ROLE_VARIANT: Record<ActorRole, 'muted' | 'warning' | 'success' | 'info' | 'destructive' | 'secondary'> = {
  AI: 'info',
  STAFF: 'warning',
  MANAGER: 'success',
  SYSTEM: 'muted',
};

const VERSION_STATUS_KEY: Record<VersionStatus, string> = {
  PENDING: 'documentDetail.statusPending',
  IN_REVIEW: 'documentDetail.statusInReview',
  APPROVED: 'documentDetail.statusApproved',
  PUBLISHED: 'documentDetail.statusPublished',
  REJECTED: 'documentDetail.statusRejected',
  SUPERSEDED: 'documentDetail.statusSuperseded',
};

const ROLE_LABEL_KEY: Record<ActorRole, string> = {
  AI: 'documentDetail.roleAI',
  STAFF: 'documentDetail.roleStaff',
  MANAGER: 'documentDetail.roleManager',
  SYSTEM: 'documentDetail.roleSystem',
};

function StatusBadge({ status, t }: { status: VersionStatus; t: (k: string) => string }) {
  return <Badge variant={STATUS_VARIANT[status]}>{t(VERSION_STATUS_KEY[status])}</Badge>;
}

function RoleBadge({ role, t }: { role: ActorRole; t: (k: string) => string }) {
  return <Badge variant={ROLE_VARIANT[role]}>{t(ROLE_LABEL_KEY[role])}</Badge>;
}

function DiffViewer({ diff }: { diff: string }) {
  return (
    <pre className="font-mono text-xs whitespace-pre-wrap bg-muted/50 rounded p-3 overflow-auto max-h-96">
      {diff.split('\n').map((line, i) => {
        if (line.startsWith('+')) return <div key={i} className="text-green-600 dark:text-green-400">{line}</div>;
        if (line.startsWith('-')) return <div key={i} className="text-red-600 dark:text-red-400">{line}</div>;
        return <div key={i} className="text-muted-foreground">{line}</div>;
      })}
    </pre>
  );
}

export default function DocumentDetailPage() {
  const { t } = useTranslation();
  const { workspaceId, documentId } = useParams<{ workspaceId: string; documentId: string }>();

  const [doc, setDoc] = useState<Document | null>(null);
  const [timeline, setTimeline] = useState<DocumentVersionSummary[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<DocumentVersion | null>(null);
  const [editBody, setEditBody] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<boolean>(false);
  const [diffText, setDiffText] = useState<string | null>(null);
  const [diffLoading, setDiffLoading] = useState<boolean>(false);
  const [driftAlerts, setDriftAlerts] = useState<DriftAlert[]>([]);
  const [links, setLinks] = useState<CodeDocumentLink[]>([]);
  const [generating, setGenerating] = useState<boolean>(false);
  const [genError, setGenError] = useState<string | null>(null);

  // Commit-to-GitHub dialog state
  const [showCommitDialog, setShowCommitDialog] = useState<boolean>(false);
  const [commitRepositoryId, setCommitRepositoryId] = useState<string>('');
  const [commitFilePath, setCommitFilePath] = useState<string>('');
  const [commitBranch, setCommitBranch] = useState<string>('');
  const [commitMessage, setCommitMessage] = useState<string>('');
  const [prTitle, setPrTitle] = useState<string>('');
  const [prBody, setPrBody] = useState<string>('');
  const [committing, setCommitting] = useState<boolean>(false);
  const [commitError, setCommitError] = useState<string | null>(null);
  const [commitSuccess, setCommitSuccess] = useState<{ url: string; branch: string } | null>(null);
  const [commitRepos, setCommitRepos] = useState<import('../services/github').Repository[]>([]);
  const [commitReposLoading, setCommitReposLoading] = useState<boolean>(false);
  const [tab, setTab] = useState<string>('content');
  const [docTags, setDocTags] = useState<DocumentTag[]>([]);

  useEffect(() => {
    if (workspaceId && documentId) {
      tagsApi.getDocumentTags(workspaceId, documentId)
        .then(data => setDocTags(data))
        .catch(err => console.error("Lỗi tải tags:", err));
    }
  }, [workspaceId, documentId]);

  async function handleAddNewTag() {
    if (!workspaceId || !documentId || !newTagName.trim()) return;
    setAddingTag(true);
    try {
      //API call to create a new tag
      const newTag = await tagsApi.createTag(workspaceId, newTagName.trim(), newTagColor);
      //API call to assign the new tag to the document
      await tagsApi.assignTag(workspaceId, documentId, newTag.id);
      // Update the local state to include the new tag
      setDocTags((prev) => [...prev, newTag]);
      // Reset the form and close the dialog
      setShowAddTag(false);
      setNewTagName('');
      setNewTagColor('#3b82f6');
    } catch (err) {
      console.error("Lỗi thêm thẻ:", err);
      alert("Lỗi khi thêm thẻ: " + describeError(err));
    } finally {
      setAddingTag(false);
    }
  }

  /*    Tag Management   */
  const [showAddTag, setShowAddTag] = useState<boolean>(false);
  const [newTagName, setNewTagName] = useState<string>('');
  const [newTagColor, setNewTagColor] = useState<string>('#3b82f6'); // Mặc định màu xanh dương
  const [addingTag, setAddingTag] = useState<boolean>(false);

  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Link entity dialog
  const [showLinkEntity, setShowLinkEntity] = useState<boolean>(false);
  const [codeEntities, setCodeEntities] = useState<CodeEntity[]>([]);
  const [selectedEntityId, setSelectedEntityId] = useState<string>('');
  const [linkingEntity, setLinkingEntity] = useState<boolean>(false);
  const [entitiesLoading, setEntitiesLoading] = useState<boolean>(false);
  const [entityLinkSearch, setEntityLinkSearch] = useState<string>('');

  // Attach-repository flow
  const [showAttachRepo, setShowAttachRepo] = useState<boolean>(false);
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<string>('');
  const [attachingRepo, setAttachingRepo] = useState<boolean>(false);
  const [attachRepoError, setAttachRepoError] = useState<string | null>(null);

  // Resolve drift dialog
  const [resolveAlertId, setResolveAlertId] = useState<string | null>(null);
  const [resolveResolution, setResolveResolution] = useState<string>('FIXED');

  // New version dialog
  const [showNewVersion, setShowNewVersion] = useState<boolean>(false);
  const [newBody, setNewBody] = useState<string>('');
  const [newSummary, setNewSummary] = useState<string>('');
  const [creatingVersion, setCreatingVersion] = useState<boolean>(false);

  // Grounding evidence state
  const [groundingFor, setGroundingFor] = useState<{ versionId: string; sources: { qualifiedName: string; kind: string; file: string; lines: number[]; signature: string }[] } | null>(null);
  const [groundingLoading, setGroundingLoading] = useState<boolean>(false);

  // Trigger commit dialog
  const [commitFor, setCommitFor] = useState<{ versionNumber: number; commit: import('../services/documents').TriggerCommitSummary } | null>(null);
  const [commitLoading, setCommitLoading] = useState<boolean>(false);

  // Template selector + preview state
  const [templates, setTemplates] = useState<DocTemplate[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState<string>('MODULE_GUIDE');
  const [previewOpen, setPreviewOpen] = useState<boolean>(false);
  const [previewText, setPreviewText] = useState<string>('');
  const [previewConfidence, setPreviewConfidence] = useState<number>(0);
  const [previewing, setPreviewing] = useState<boolean>(false);

  async function handleCreateVersion() {
    if (!workspaceId || !documentId || !newBody.trim()) return;
    setCreatingVersion(true);
    try {
      await documentsApi.appendVersion(workspaceId, documentId, {
        bodyMarkdown: newBody,
        changeSummary: newSummary.trim() || 'Manual version',
      });
      setShowNewVersion(false);
      setNewBody('');
      setNewSummary('');
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setCreatingVersion(false);
    }
  }

  const load = useCallback(async () => {
    if (!workspaceId || !documentId) return;
    setLoading(true);
    setError(null);
    try {
      const [d, tl] = await Promise.all([
        documentsApi.get(workspaceId, documentId),
        documentsApi.timeline(documentId),
      ]);
      setDoc(d);
      setTimeline(tl);

      if (tl.length > 0) {
        const head = tl[0];
        const full = await documentsApi.version(documentId, head.versionNumber);
        setSelectedVersion(full);
        setEditBody(full.bodyMarkdown);
      }
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, documentId]);

  const loadDrift = useCallback(async () => {
    if (!workspaceId || !documentId) return;
    try {
      const alerts = await driftApi.list(workspaceId, { status: 'OPEN' });
      setDriftAlerts(alerts.filter((a) => a.documentId === documentId));
    } catch { /* non-critical */ }
  }, [workspaceId, documentId]);

  const loadLinks = useCallback(async () => {
    if (!workspaceId || !documentId) return;
    try {
      const l = await linksApi.list(workspaceId, documentId);
      setLinks(l);
    } catch { /* non-critical */ }
  }, [workspaceId, documentId]);

  const loadCodeEntities = useCallback(async () => {
    if (!workspaceId || !doc?.repositoryId) {
      setCodeEntities([]);
      return;
    }
    setEntitiesLoading(true);
    try {
      const entities = await codeApi.listEntities(workspaceId, doc.repositoryId);
      setCodeEntities(entities);
    } catch (err) {
      // non-critical
      setCodeEntities([]);
    } finally {
      setEntitiesLoading(false);
    }
  }, [workspaceId, doc?.repositoryId]);

  const openAttachRepoDialog = useCallback(async () => {
    if (!workspaceId) return;
    setShowAttachRepo(true);
    setSelectedRepoId(doc?.repositoryId ?? '');
    setAttachRepoError(null);
    // Lazy-load repositories every time the dialog opens so we always see latest.
    try {
      const repos = await githubApi.listRepositories(workspaceId);
      setRepositories(repos);
    } catch (err) {
      setAttachRepoError(describeError(err));
    }
  }, [workspaceId, doc?.repositoryId]);

  async function handleAttachRepo() {
    if (!workspaceId || !documentId || !doc) return;
    setAttachingRepo(true);
    setAttachRepoError(null);
    try {
      const updated = await documentsApi.update(workspaceId, documentId, {
        title: doc.title,
        repositoryId: selectedRepoId ? selectedRepoId : undefined,
        autoUpdateEnabled: doc.autoUpdateEnabled,
      });
      setDoc(updated);
      setShowAttachRepo(false);
      // Reload entities so the Link dialog reflects the new repo.
      await loadCodeEntities();
    } catch (err) {
      setAttachRepoError(describeError(err));
    } finally {
      setAttachingRepo(false);
    }
  }

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadTemplates();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  useEffect(() => {
    if (tab === 'drift') void loadDrift();
    if (tab === 'links') {
      void loadLinks();
      void loadCodeEntities();
    }
  }, [tab, loadDrift, loadLinks, loadCodeEntities]);

  // Auto-save debounce
  useEffect(() => {
    if (!selectedVersion || editBody === selectedVersion.bodyMarkdown) return;
    const timer = setTimeout(async () => {
      if (!workspaceId || !documentId) return;
      setSaving(true);
      setSaveError(null);
      try {
        const updated = await documentsApi.appendVersion(workspaceId, documentId, {
          bodyMarkdown: editBody,
          changeSummary: 'Manual edit',
        });
        setSelectedVersion(updated);
        setSaveSuccess(true);
        setTimeout(() => setSaveSuccess(false), 2000);
        await load();
      } catch (err) {
        setSaveError(describeError(err));
      } finally {
        setSaving(false);
      }
    }, 1500);
    return () => clearTimeout(timer);
  }, [editBody]);

  async function handleVersionClick(v: DocumentVersionSummary) {
    if (!workspaceId || !documentId) return;
    const full = await documentsApi.version(documentId, v.versionNumber);
    setSelectedVersion(full);
    setEditBody(full.bodyMarkdown);
  }

  async function handleViewDiff() {
    if (!workspaceId || !documentId || !selectedVersion || timeline.length < 2) return;
    setDiffLoading(true);
    try {
      const prev = timeline.find((t) => t.versionNumber === selectedVersion.versionNumber - 1);
      if (prev) {
        const d = await documentsApi.diff(documentId, prev.versionNumber, selectedVersion.versionNumber);
        setDiffText(d.unifiedDiff);
      } else {
        setDiffText(`// Version ${selectedVersion.versionNumber} — no previous version to diff against`);
      }
    } catch {
      setDiffText('// Diff not available');
    } finally {
      setDiffLoading(false);
    }
  }

  async function handleCompareAiVsPublished() {
    if (!workspaceId || !documentId) return;
    setDiffLoading(true);
    try {
      const d = await documentsApi.diffAgainstPublished(documentId);
      setDiffText(d.unifiedDiff);
    } catch (err) {
      setDiffText(`// ${describeError(err)}`);
    } finally {
      setDiffLoading(false);
    }
  }

  async function handleShowGrounding(versionId: string) {
    if (!workspaceId || !documentId) return;
    setGroundingLoading(true);
    setGroundingFor({ versionId, sources: [] });
    try {
      const data = await aiApi.fetchGrounding(workspaceId, documentId, versionId);
      setGroundingFor({ versionId, sources: data.sources || [] });
    } catch (err) {
      setGroundingFor({ versionId, sources: [] });
    } finally {
      setGroundingLoading(false);
    }
  }

  async function handleShowTriggerCommit(versionNumber: number) {
    if (!documentId) return;
    setCommitLoading(true);
    setCommitFor({ versionNumber, commit: { sha: '', shortSha: '', message: null, authorName: null, authoredAt: null, url: null, id: null } });
    try {
      const data = await documentsApi.triggerCommit(documentId, versionNumber);
      if (data) {
        setCommitFor({ versionNumber, commit: data });
      } else {
        setCommitFor(null);
      }
    } catch (err) {
      setCommitFor(null);
    } finally {
      setCommitLoading(false);
    }
  }

  async function handlePublishVersion(versionNumber: number) {
    if (!workspaceId || !documentId) return;
    try {
      await documentsApi.publishVersion(workspaceId, documentId, versionNumber);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function handleRollback(versionNumber: number) {
    if (!workspaceId || !documentId) return;
    if (!window.confirm(`Roll back to v${versionNumber}? This creates a new version.`)) return;
    try {
      await documentsApi.rollback(workspaceId, documentId, versionNumber);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function handleGenerateWithAI() {
    if (!workspaceId || !documentId || !doc?.repositoryId) return;
    setGenerating(true);
    setGenError(null);
    try {
      await aiApi.generate(workspaceId, documentId, [], selectedTemplate);
      await load();
    } catch (err) {
      setGenError(describeError(err));
    } finally {
      setGenerating(false);
    }
  }

  async function handlePreviewGenerate() {
    if (!workspaceId || !documentId) return;
    setPreviewing(true);
    setGenError(null);
    setPreviewText('');
    setPreviewConfidence(0);
    setPreviewOpen(true);
    try {
      const result = await aiApi.previewGenerate(workspaceId, documentId, [], selectedTemplate);
      setPreviewText(result.markdown);
      setPreviewConfidence(result.confidenceScore);
    } catch (err) {
      setGenError(describeError(err));
    } finally {
      setPreviewing(false);
    }
  }

  async function loadTemplates() {
    if (!workspaceId) return;
    try {
      const data = await templatesApi.list(workspaceId);
      const all = [...(data.workspaceTemplates || []), ...(data.globalTemplates || [])];
      setTemplates(all);
      // Default to current document's template if it matches; otherwise first available.
      if (doc?.templateId) {
        const match = all.find((t) => t.id === doc.templateId);
        if (match) setSelectedTemplate(match.slug || match.name);
      } else if (all.length > 0) {
        const def = all.find((t) => t.isDefault) || all[0];
        setSelectedTemplate(def.slug || def.name);
      }
    } catch {
      // non-critical
    }
  }

  // Auto-pick the workspace's default template once both the document
  // and the template catalogue have loaded. The user can still change
  // the dropdown afterwards — this just removes an unnecessary click
  // for the common case.
  useEffect(() => {
    if (!workspaceId || !doc) return;
    if (templates.length === 0) return;
    if (doc.templateId) {
      const match = templates.find((t) => t.id === doc.templateId);
      if (match) {
        setSelectedTemplate(match.slug || match.name);
        return;
      }
    }
    const def = templates.find((t) => t.isDefault) ?? templates[0];
    if (def) setSelectedTemplate(def.slug || def.name);
  }, [templates, doc, workspaceId]);

  async function handleResolveDrift(alertId: string) {
    if (!workspaceId) return;
    try {
      await driftApi.resolve(workspaceId, alertId, resolveResolution as import('../services/drift').DriftResolution);
      setResolveAlertId(null);
      await loadDrift();
    } catch (err) {
      setError(describeError(err));
    }
  }

  const [driftScanning, setDriftScanning] = useState<boolean>(false);
  const [driftScanResult, setDriftScanResult] = useState<string | null>(null);

  async function handleTriggerDriftForDoc() {
    if (!workspaceId || !documentId || !doc?.repositoryId) return;
    setDriftScanning(true);
    setDriftScanResult(null);
    try {
      const result = await driftApi.detect(workspaceId, {
        repositoryId: doc.repositoryId,
        documentId: documentId,
        filesBefore: [],
        filesAfter: [],
      });
      setDriftScanResult(`Scan complete. ${result.ingested} new alert(s) detected.`);
      await loadDrift();
    } catch (err) {
      setDriftScanResult(describeError(err));
    } finally {
      setDriftScanning(false);
    }
  }

  async function handleLinkEntity() {
    if (!workspaceId || !documentId || !selectedEntityId) return;
    setLinkingEntity(true);
    try {
      await linksApi.link(workspaceId, documentId, selectedEntityId);
      setShowLinkEntity(false);
      setSelectedEntityId('');
      await loadLinks();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLinkingEntity(false);
    }
  }

  async function handleClearAllLinks() {
    if (!workspaceId || !documentId) return;
    if (!window.confirm(t('documentDetail.confirmClearLinks', 'Remove all code links from this document?'))) return;
    try {
      await linksApi.clear(workspaceId, documentId);
      await loadLinks();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function openLinkDialog() {
    if (!doc?.repositoryId) {
      // Open the attach-repo flow directly so the user can fix it in 2 clicks.
      void openAttachRepoDialog();
      return;
    }
    setShowLinkEntity(true);
    setSelectedEntityId('');
    setEntityLinkSearch('');
    // Always refresh entities when opening the dialog so the dropdown is current.
    void loadCodeEntities();
  }

  async function loadCommitRepos() {
    if (!workspaceId) return;
    setCommitReposLoading(true);
    try {
      const repos = await githubApi.listRepositories(workspaceId);
      setCommitRepos(repos);
      // Auto-select doc's repository if not already selected
      if (doc?.repositoryId && repos.some(r => r.id === doc.repositoryId)) {
        setCommitRepositoryId(doc.repositoryId);
      } else if (repos.length > 0 && !commitRepositoryId) {
        setCommitRepositoryId(repos[0].id);
      }
    } catch (err) {
      console.error('Failed to load repositories:', err);
    } finally {
      setCommitReposLoading(false);
    }
  }

  function openCommitDialog() {
    console.log('[CommitDialog] openCommitDialog called', { doc: doc?.id, repo: doc?.repositoryId });
    if (!doc) return;
    setCommitRepositoryId(doc.repositoryId || '');
    setCommitFilePath(`docs/${doc.slug || doc.id}.md`);
    setCommitBranch('');
    setCommitMessage(`docs: update ${doc.slug || doc.title}`);
    setPrTitle(`docs: ${doc.title}`);
    setPrBody(
      `This pull request was opened automatically by LivingDocs.\n\n` +
      `Source document: \`${doc.slug || doc.id}\`\n` +
      `Status: ${doc.status}\n\n` +
      `Please review and merge if the content looks correct.`,
    );
    setCommitError(null);
    setCommitSuccess(null);
    setShowCommitDialog(true);
    // Load repositories for the dropdown
    void loadCommitRepos();
    console.log('[CommitDialog] showCommitDialog set to true');
  }

  async function handleCommitToGithub() {
    if (!workspaceId || !documentId || !commitRepositoryId) {
      setCommitError('Please select a repository.');
      return;
    }
    if (!commitFilePath.trim() || !commitMessage.trim() || !prTitle.trim()) {
      setCommitError('Please fill in file path, commit message, and PR title.');
      return;
    }
    setCommitting(true);
    setCommitError(null);
    try {
      // Build the file content as a small Markdown document so reviewers see
      // a meaningful diff. Include frontmatter with title + slug.
      if (!doc) {
        setCommitError('Document not loaded.');
        return;
      }
      const content = buildCommitContent(doc);
      const result = await githubApi.commitDocument(workspaceId, commitRepositoryId, {
        filePath: commitFilePath.trim(),
        content,
        commitMessage: commitMessage.trim(),
        prTitle: prTitle.trim(),
        prBody: prBody.trim() || undefined,
        headBranch: commitBranch.trim() || undefined,
      });
      setCommitSuccess({ url: result.pullRequestUrl, branch: result.headBranch });
    } catch (err) {
      setCommitError(describeError(err));
    } finally {
      setCommitting(false);
    }
  }

  function buildCommitContent(d: Document): string {
    const body = editBody && editBody.trim().length > 0
      ? editBody
      : (selectedVersion?.bodyMarkdown ?? '');
    const fm = [
      '---',
      `title: ${d.title}`,
      d.slug ? `slug: ${d.slug}` : null,
      d.docType ? `type: ${d.docType}` : null,
      d.summary ? `summary: ${d.summary}` : null,
      `status: ${d.status}`,
      `source: livingdocs://workspaces/${workspaceId}/documents/${documentId}`,
      `committed_at: ${new Date().toISOString()}`,
      '---',
      '',
    ].filter(Boolean).join('\n');
    return `${fm}\n${body}\n`;
  }

  if (!workspaceId || !documentId) return <EmptyState title={t('documentDetail.missingIds')} />;
  if (loading) return <LoadingState message={t('documentDetail.loading')} />;
  if (error) return <ErrorState message={error} />;
  if (!doc) return <EmptyState title={t('documentDetail.notFound')} />;

  return (
    <div className="space-y-4">
      {/* Header */}
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}/documents`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> All documents
        </Link>
      </Button>

      <div className="flex items-start justify-between gap-4">
        <div className="space-y-1 min-w-0">
          <h1 className="text-2xl font-bold tracking-tight truncate">{doc.title}</h1>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <code className="text-xs bg-muted px-1.5 py-0.5 rounded">{doc.slug}</code>
            <Badge variant="muted">{doc.docType}</Badge>
            <button 
              type="button"
              onClick={async () => {
                try {
                  const updatedDoc = await documentsApi.toggleAutoUpdate(workspaceId, documentId, !doc.autoUpdateEnabled);
                  setDoc(updatedDoc);
                } catch (err) {
                  alert("Lỗi khi thay đổi trạng thái tự động cập nhật.");
                }
              }}
              className="transition-transform active:scale-95 outline-none"
              title="Nhấn để Bật/Tắt chế độ tự động cập nhật bằng AI khi có code mới"
            >
              <Badge 
                variant={doc.autoUpdateEnabled ? 'info' : 'muted'} 
                className="cursor-pointer hover:opacity-80 flex items-center gap-1"
              >
                {doc.autoUpdateEnabled ? (
                  <><Shield className="w-3 h-3" /> Auto-update: ON</>
                ) : (
                  <>Manual: OFF</>
                )}
              </Badge>
            </button>
          </div>
        </div>
        {/* Tag Management */}
          <div className="pt-3 flex items-center gap-2 flex-wrap min-h-[32px]">
            <DocumentTags tags={docTags} />

            <Dialog open={showAddTag} onOpenChange={setShowAddTag}>
              <DialogTrigger asChild>
                <Button 
                  variant="outline" 
                  size="sm" 
                  className="h-[24px] text-[11px] px-2 py-0 border-dashed font-medium text-muted-foreground hover:text-foreground"
                >
                  <Plus className="h-3 w-3 mr-1" /> {t('documentDetail.addTag', 'Thêm thẻ')}
                </Button>
              </DialogTrigger>
              <DialogContent className="sm:max-w-[400px]">
                <DialogHeader>
                  <DialogTitle>{t('documentDetail.newTagTitle', 'Thêm thẻ mới')}</DialogTitle>
                  <DialogDescription>
                    {t('documentDetail.newTagDesc', 'Tạo thẻ mới và gắn vào tài liệu này.')}
                  </DialogDescription>
                </DialogHeader>
                <div className="grid gap-4 py-4">
                  <div className="grid grid-cols-4 items-center gap-4">
                    <Label htmlFor="tagName" className="text-right">{t('documentDetail.tagName', 'Tên thẻ')}</Label>
                    <Input
                      id="tagName"
                      value={newTagName}
                      onChange={(e) => setNewTagName(e.target.value)}
                      className="col-span-3"
                      placeholder={t('documentDetail.tagNamePlaceholder', 'VD: Quan trọng, Backend...')}
                      maxLength={30}
                    />
                  </div>
                  <div className="grid grid-cols-4 items-center gap-4">
                    <Label className="text-right">{t('documentDetail.tagColor', 'Màu sắc')}</Label>
                    <div className="col-span-3 flex items-center gap-3 flex-wrap">
                      {['#EF4444', '#F97316', '#EAB308', '#22C55E', '#3B82F6', '#A855F7', '#64748B'].map((color) => (
                        <button
                          key={color}
                          type="button"
                          onClick={() => setNewTagColor(color)}
                          className={`w-6 h-6 rounded-full cursor-pointer transition-all border border-gray-600 shadow-sm
                            ${newTagColor === color ? 'ring-2 ring-offset-2 ring-primary scale-110' : 'hover:scale-110 opacity-80'}`}
                          style={{ backgroundColor: color }}
                          title={color}
                        />
                      ))}
                    </div>
                  </div>
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setShowAddTag(false)}>
                    {t('common.cancel', 'Hủy')}
                  </Button>
                  <Button onClick={() => void handleAddNewTag()} disabled={!newTagName.trim() || addingTag}>
                    {addingTag ? t('common.saving', 'Đang lưu...') : t('documentDetail.saveTag', 'Lưu thẻ')}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
          {/* --------------------------------------- */}
        <div className="flex gap-2 flex-shrink-0">
          <div className="flex items-center gap-1">
            <Select value={selectedTemplate} onValueChange={setSelectedTemplate}>
              <SelectTrigger className="h-9 w-[170px] text-xs">
                <SelectValue placeholder={t('documentDetail.templatePlaceholder', 'Template')} />
              </SelectTrigger>
              <SelectContent>
                {templates.length === 0 ? (
                  <SelectItem value="MODULE_GUIDE">MODULE_GUIDE</SelectItem>
                ) : (
                  templates.map((t) => (
                    <SelectItem key={t.id} value={t.slug || t.name}>
                      {t.name}
                    </SelectItem>
                  ))
                )}
              </SelectContent>
            </Select>
          </div>
          <Button size="sm" variant="outline" onClick={() => void handlePreviewGenerate()} disabled={previewing || generating}>
            <Eye className="mr-1 h-4 w-4" /> {previewing ? t('documentDetail.previewing', 'Previewing…') : t('documentDetail.preview', 'Preview')}
          </Button>
          <Button size="sm" onClick={() => void handleGenerateWithAI()} disabled={generating}>
            <Bot className="mr-1 h-4 w-4" /> {generating ? t('documentDetail.generating') : t('documentDetail.generateAi')}
          </Button>
          <Button
            size="sm"
            variant="outline"
            onClick={openCommitDialog}
            disabled={!doc?.repositoryId}
            title={
              !doc?.repositoryId
                ? t('documentDetail.commitNeedsRepo', 'Attach a repository first to commit.')
                : undefined
            }
          >
            <Upload className="mr-1 h-4 w-4" /> {t('documentDetail.commitToGithub', 'Commit to GitHub')}
          </Button>
          <RegenerateDocButton workspaceId={workspaceId} documentId={documentId} />
          <Dialog open={showNewVersion} onOpenChange={setShowNewVersion}>
            <DialogTrigger asChild>
              <Button size="sm" variant="outline">
                <Plus className="mr-1 h-4 w-4" /> New version
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-2xl">
              <DialogHeader>
                <DialogTitle>{t('documentDetail.newVersionTitle', 'Tạo version mới')}</DialogTitle>
                <DialogDescription>
                  {t('documentDetail.newVersionDesc', 'Tạo một version tài liệu mới với nội dung tùy chỉnh.')}
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-3 my-2">
                <div className="space-y-1.5">
                  <Label>{t('documentDetail.changeSummary', 'Change summary')}</Label>
                  <Input
                    value={newSummary}
                    onChange={(e) => setNewSummary(e.target.value)}
                    placeholder={t('documentDetail.changeSummaryPlaceholder', 'Mô tả ngắn về thay đổi…')}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>{t('documentDetail.bodyMarkdown', 'Body (Markdown)')}</Label>
                  <Textarea
                    value={newBody}
                    onChange={(e) => setNewBody(e.target.value)}
                    rows={10}
                    className="font-mono text-sm resize-y"
                  />
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setShowNewVersion(false)}>
                  {t('common.cancel', 'Hủy')}
                </Button>
                <Button
                  onClick={() => void handleCreateVersion()}
                  disabled={creatingVersion || !newBody.trim()}
                >
                  {creatingVersion
                    ? t('common.creating', 'Đang tạo…')
                    : t('documentDetail.createVersion', 'Tạo version')}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </div>

      {genError && <ErrorState message={genError} />}

      {/* Quick Status Bar */}
      {doc.repositoryId && (
        <div className="flex items-center gap-4 rounded-md border bg-muted/30 px-4 py-2 text-sm">
          <div className="flex items-center gap-2">
            <Github className="h-4 w-4 text-muted-foreground" />
            <Link
              to={`/workspaces/${workspaceId}/repositories`}
              className="hover:text-primary"
            >
              {doc.repositoryId.slice(0, 8)}...
            </Link>
          </div>
          <div className="h-4 w-px bg-border" />
          <div className="flex items-center gap-2 text-muted-foreground">
            <Shield className="h-4 w-4" />
            <span>
              Auto-update: {doc.autoUpdateEnabled ? 'ON' : 'OFF'}
            </span>
          </div>
          <div className="h-4 w-px bg-border" />
          <div className="flex items-center gap-2 text-muted-foreground">
            <Link2 className="h-4 w-4" />
            <span>{links.length} linked entities</span>
          </div>
        </div>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="content">Content</TabsTrigger>
          <TabsTrigger value="versions">Versions</TabsTrigger>
          <TabsTrigger value="links">Links</TabsTrigger>
          <TabsTrigger value="drift">Drift</TabsTrigger>
        </TabsList>

        {/* ── CONTENT TAB ── */}
        <TabsContent value="content" className="space-y-4">
          <div className="grid gap-4 lg:grid-cols-3">
            {/* Version timeline */}
            <Card className="lg:col-span-1">
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center gap-2">
                  <History className="h-4 w-4" /> Version history
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-1">
                {timeline.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No versions yet.</p>
                ) : (
                  timeline.map((v) => (
                    <button
                      key={v.id}
                      type="button"
                      onClick={() => void handleVersionClick(v)}
                      className={`w-full text-left rounded-md border px-3 py-2 text-xs transition-colors hover:bg-muted ${
                        selectedVersion?.versionNumber === v.versionNumber
                          ? 'border-primary bg-muted/70'
                          : 'border-transparent'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="font-semibold">v{v.versionNumber}</span>
                        <div className="flex gap-1">
                          <StatusBadge status={v.status} t={t} />
                        </div>
                      </div>
                      <div className="flex gap-1 mb-1">
                        <RoleBadge role={v.actorRole} t={t} />
                      </div>
                      <div className="text-muted-foreground">
                        {format(new Date(v.createdAt), 'MMM d, HH:mm')}
                        {v.confidenceScore !== null && ` · ${(v.confidenceScore * 100).toFixed(0)}%`}
                      </div>
                      {v.changeSummary && (
                        <div className="mt-1 truncate text-muted-foreground">{v.changeSummary}</div>
                      )}
                      {v.actorRole === 'AI' && v.sourceCommitShort && (
                        <button
                          type="button"
                          onClick={(e) => { e.stopPropagation(); void handleShowTriggerCommit(v.versionNumber); }}
                          className="mt-1 inline-flex items-center gap-1 text-[10px] font-mono text-blue-600 dark:text-blue-400 hover:underline"
                          title="Click to view the commit that triggered this auto-update"
                        >
                          <Bot className="h-2.5 w-2.5" /> triggered by {v.sourceCommitShort}
                        </button>
                      )}
                      <div className="flex gap-1 mt-1.5">
                        {v.status !== 'PUBLISHED' && (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-6 text-xs px-1.5"
                            onClick={(e) => { e.stopPropagation(); void handlePublishVersion(v.versionNumber); }}
                          >
                            <Upload className="h-3 w-3 mr-0.5" /> Publish
                          </Button>
                        )}
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-6 text-xs px-1.5"
                          onClick={(e) => { e.stopPropagation(); void handleRollback(v.versionNumber); }}
                        >
                          <RotateCcw className="h-3 w-3 mr-0.5" /> Rollback
                        </Button>
                        {v.actorRole === 'AI' && (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-6 text-xs px-1.5"
                            onClick={(e) => { e.stopPropagation(); void handleShowGrounding(v.id); }}
                            title="View grounding evidence — source code references used by the AI"
                          >
                            <Sparkles className="h-3 w-3 mr-0.5" /> Grounding
                          </Button>
                        )}
                      </div>
                    </button>
                  ))
                )}
              </CardContent>
            </Card>

            {/* Editor panel */}
            <Card className="lg:col-span-2">
              <CardHeader className="pb-2">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm flex items-center gap-2">
                    {selectedVersion ? `v${selectedVersion.versionNumber} body` : t('documentDetail.documentBody')}
                    {selectedVersion?.confidenceScore !== null && selectedVersion?.confidenceScore !== undefined && (
                      <Badge
                        variant={
                          (selectedVersion.confidenceScore ?? 0) >= 0.7
                            ? 'success'
                            : (selectedVersion.confidenceScore ?? 0) >= 0.4
                              ? 'warning'
                              : 'destructive'
                        }
                        className="text-[10px]"
                        title="AI confidence score for this version"
                      >
                        {Math.round((selectedVersion.confidenceScore ?? 0) * 100)}% confidence
                      </Badge>
                    )}
                  </CardTitle>
                  <div className="flex items-center gap-2">
                    {saving && <span className="text-xs text-muted-foreground animate-pulse">Saving…</span>}
                    {saveSuccess && <span className="text-xs text-green-600 flex items-center gap-1"><Check className="h-3 w-3" /> Saved</span>}
                    <Button size="sm" variant="outline" onClick={() => void handleViewDiff()} disabled={diffLoading}>
                      <Eye className="mr-1 h-4 w-4" /> View diff
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => void handleCompareAiVsPublished()} disabled={diffLoading} title="Compare the most recent AI version against the most recent published version">
                      <Sparkles className="mr-1 h-4 w-4" /> AI vs Published
                    </Button>
                  </div>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <Textarea
                  value={editBody}
                  onChange={(e) => setEditBody(e.target.value)}
                  rows={20}
                  className="font-mono text-sm resize-y"
                  placeholder={t('documentDetail.writeMarkdownPlaceholder')}
                />
                {saveError && <ErrorState message={saveError} />}
              </CardContent>
            </Card>
          </div>

          {/* Diff dialog */}
          {diffText !== null && (
            <Dialog open={!!diffText} onOpenChange={() => setDiffText(null)}>
              <DialogContent className="max-w-3xl">
                <DialogHeader>
                  <DialogTitle>Version diff</DialogTitle>
                </DialogHeader>
                <DiffViewer diff={diffText} />
                <DialogFooter>
                  <DialogClose asChild><Button variant="outline">Close</Button></DialogClose>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          )}
        </TabsContent>

        {/* ── VERSIONS TAB ── */}
        <TabsContent value="versions">
          <Card>
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <History className="h-4 w-4" /> All versions
              </CardTitle>
            </CardHeader>
            <CardContent>
              {timeline.length === 0 ? (
                <EmptyState title={t('documentDetail.noVersions')} description={t('documentDetail.noVersionsDesc')} />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>#</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Role</TableHead>
                      <TableHead>Summary</TableHead>
                      <TableHead>Confidence</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead>Commit</TableHead>
                      <TableHead>Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {timeline.map((v) => (
                      <TableRow key={v.id}>
                        <TableCell className="font-medium">v{v.versionNumber}</TableCell>
                        <TableCell><StatusBadge status={v.status} t={t} /></TableCell>
                        <TableCell><RoleBadge role={v.actorRole} t={t} /></TableCell>
                        <TableCell className="text-sm max-w-[200px] truncate">{v.changeSummary ?? '—'}</TableCell>
                        <TableCell>
                          {v.confidenceScore !== null
                            ? `${(v.confidenceScore * 100).toFixed(0)}%`
                            : '—'}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {format(new Date(v.createdAt), 'MMM d, yyyy HH:mm')}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground font-mono">
                          {v.sourceCommitSha ? v.sourceCommitSha.slice(0, 7) : '—'}
                        </TableCell>
                        <TableCell>
                          <div className="flex gap-1">
                            {v.status !== 'PUBLISHED' && (
                              <Button
                                size="sm"
                                variant="outline"
                                className="h-7 text-xs"
                                onClick={() => void handlePublishVersion(v.versionNumber)}
                              >
                                Publish
                              </Button>
                            )}
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-7 text-xs"
                              onClick={() => void handleRollback(v.versionNumber)}
                            >
                              <RotateCcw className="h-3 w-3 mr-1" /> Rollback
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── LINKS TAB ── */}
        <TabsContent value="links" className="space-y-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-base flex items-center gap-2">
                  <Link2 className="h-4 w-4" /> Code–document links
                </CardTitle>
                <div className="flex gap-2">
                  {links.length > 0 && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void handleClearAllLinks()}
                      className="text-destructive"
                    >
                      {t('documentDetail.clearAllLinks', 'Clear all')}
                    </Button>
                  )}
                  <Dialog open={showLinkEntity} onOpenChange={setShowLinkEntity}>
                    <DialogTrigger asChild>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void openLinkDialog()}
                      >
                        <Link2 className="mr-1 h-4 w-4" /> {t('documentDetail.linkEntity', 'Link entity')}
                      </Button>
                    </DialogTrigger>
                    <DialogContent>
                      <DialogHeader>
                        <DialogTitle>{t('documentDetail.linkEntityTitle', 'Link code entity')}</DialogTitle>
                        <DialogDescription>
                          {t('documentDetail.linkEntityDesc', 'Select a code entity to link to this document. The AI will use this link as grounding when regenerating.')}
                        </DialogDescription>
                      </DialogHeader>
                      <div className="space-y-3">
                        {entitiesLoading ? (
                          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
                            <Loader2 className="h-4 w-4 animate-spin" />
                            {t('documentDetail.loadingEntities', 'Loading code entities…')}
                          </div>
                        ) : codeEntities.length === 0 ? (
                          <div className="space-y-3">
                            <div className="text-sm text-muted-foreground space-y-2 py-2">
                              <p>
                                {t('documentDetail.noEntities', 'No code entities found for this repository.')}
                              </p>
                              <p className="text-xs">
                                {t('documentDetail.noEntitiesHint', 'Code entities are ingested automatically when the repository is synced.')}
                              </p>
                            </div>
                            {/* Debug info so the user can see exactly what backend returned */}
                            <div className="rounded-md border border-dashed bg-muted/30 p-2 text-[10px] font-mono text-muted-foreground space-y-0.5">
                              <div>workspaceId: <span className="text-foreground">{workspaceId}</span></div>
                              <div>repositoryId: <span className="text-foreground">{doc?.repositoryId || '(none)'}</span></div>
                              <div>entity count: <span className="text-foreground">{codeEntities.length}</span></div>
                            </div>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => void loadCodeEntities()}
                            >
                              <Loader2 className="mr-1 h-3 w-3" />
                              {t('documentDetail.retryLoadEntities', 'Retry')}
                            </Button>
                          </div>
                        ) : (
                          <>
                            {/* Search + counter so the user can find an entity among many */}
                            <div className="flex items-center gap-2">
                              <div className="relative flex-1">
                                <Input
                                  value={entityLinkSearch}
                                  onChange={(e) => setEntityLinkSearch(e.target.value)}
                                  placeholder={t('documentDetail.entityLinkSearchPlaceholder', 'Search by name, type, or file path…')}
                                  className="h-9"
                                />
                                {entityLinkSearch && (
                                  <Button
                                    variant="ghost"
                                    size="icon"
                                    className="absolute right-1 top-1/2 -translate-y-1/2 h-6 w-6"
                                    onClick={() => setEntityLinkSearch('')}
                                    aria-label="Clear"
                                  >
                                    <X className="h-3 w-3" />
                                  </Button>
                                )}
                              </div>
                              <Button
                                size="icon"
                                variant="ghost"
                                className="h-9 w-9"
                                onClick={() => void loadCodeEntities()}
                                disabled={entitiesLoading}
                                aria-label="Refresh"
                                title="Refresh entities"
                              >
                                <Loader2 className={`h-4 w-4 ${entitiesLoading ? 'animate-spin' : ''}`} />
                              </Button>
                            </div>
                            <div className="text-xs text-muted-foreground">
                              {(() => {
                                const q = entityLinkSearch.trim().toLowerCase();
                                if (!q) return `${codeEntities.length} entities available`;
                                const matched = codeEntities.filter((e) =>
                                  e.qualifiedName.toLowerCase().includes(q) ||
                                  e.simpleName.toLowerCase().includes(q) ||
                                  e.entityType.toLowerCase().includes(q) ||
                                  e.filePath.toLowerCase().includes(q)
                                ).length;
                                return `${matched} / ${codeEntities.length} match "${entityLinkSearch}"`;
                              })()}
                            </div>
                            <Select value={selectedEntityId} onValueChange={setSelectedEntityId}>
                              <SelectTrigger>
                                <SelectValue placeholder={t('documentDetail.selectEntity', 'Select entity…')} />
                              </SelectTrigger>
                              <SelectContent className="max-h-80">
                                {codeEntities
                                  .filter((e) => {
                                    const q = entityLinkSearch.trim().toLowerCase();
                                    if (!q) return true;
                                    return (
                                      e.qualifiedName.toLowerCase().includes(q) ||
                                      e.simpleName.toLowerCase().includes(q) ||
                                      e.entityType.toLowerCase().includes(q) ||
                                      e.filePath.toLowerCase().includes(q)
                                    );
                                  })
                                  .map((e) => (
                                    <SelectItem key={e.id} value={e.id}>
                                      <div className="flex flex-col gap-0.5">
                                        <div>
                                          <span className="font-mono text-[10px] text-muted-foreground">[{e.entityType}]</span>{' '}
                                          <span className="font-mono text-xs">{e.simpleName || e.qualifiedName}</span>
                                        </div>
                                        <div className="text-[10px] text-muted-foreground font-mono truncate max-w-[480px]">
                                          {e.filePath}:{e.startLine}-{e.endLine}
                                        </div>
                                      </div>
                                    </SelectItem>
                                  ))}
                              </SelectContent>
                            </Select>
                          </>
                        )}
                      </div>
                      <DialogFooter>
                        <Button variant="outline" onClick={() => setShowLinkEntity(false)}>
                          {t('common.cancel', 'Cancel')}
                        </Button>
                        <Button onClick={() => void handleLinkEntity()} disabled={!selectedEntityId || linkingEntity}>
                          {linkingEntity ? t('documentDetail.linking', 'Linking…') : t('documentDetail.linkEntity', 'Link entity')}
                        </Button>
                      </DialogFooter>
                    </DialogContent>
                  </Dialog>

                  {/* Attach repository dialog */}
                  <Dialog open={showAttachRepo} onOpenChange={(o) => { if (!o) setShowAttachRepo(false); }}>
                    <DialogContent>
                      <DialogHeader>
                        <DialogTitle>{t('documentDetail.attachRepoTitle', 'Attach a repository')}</DialogTitle>
                        <DialogDescription>
                          {t('documentDetail.attachRepoDesc', 'Pick a GitHub repository this document should be associated with. The AI will use it as grounding context, and its code entities will appear in the link picker.')}
                        </DialogDescription>
                      </DialogHeader>
                      <div className="space-y-3">
                        <Label htmlFor="attach-repo-select">{t('documentDetail.attachRepoLabel', 'Repository')}</Label>
                        {repositories.length === 0 ? (
                          <p className="text-xs text-muted-foreground">
                            {t('documentDetail.attachRepoEmpty', 'No repositories found in this workspace. Add one from the Repositories page first.')}
                          </p>
                        ) : (
                          <Select value={selectedRepoId} onValueChange={setSelectedRepoId}>
                            <SelectTrigger id="attach-repo-select">
                              <SelectValue placeholder={t('documentDetail.attachRepoPlaceholder', 'Select a repository…')} />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="">{t('documentDetail.attachRepoNone', '— None —')}</SelectItem>
                              {repositories.map((r) => (
                                <SelectItem key={r.id} value={r.id}>
                                  {r.fullName}
                                  {r.description ? <span className="text-xs text-muted-foreground"> · {r.description.slice(0, 60)}</span> : null}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        )}
                        {attachRepoError && (
                          <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                            {attachRepoError}
                          </div>
                        )}
                      </div>
                      <DialogFooter>
                        <Button variant="outline" onClick={() => setShowAttachRepo(false)} disabled={attachingRepo}>
                          {t('common.cancel', 'Cancel')}
                        </Button>
                        <Button onClick={() => void handleAttachRepo()} disabled={attachingRepo}>
                          {attachingRepo ? (
                            <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('documentDetail.attaching', 'Attaching…')}</>
                          ) : (
                            t('documentDetail.attachRepoSave', 'Attach')
                          )}
                        </Button>
                      </DialogFooter>
                    </DialogContent>
                  </Dialog>

                  {/* Commit-to-GitHub dialog moved out of Tabs so it works on every tab */}
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {!doc?.repositoryId ? (
                <div className="space-y-3">
                  <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300 flex items-start gap-3">
                    <AlertCircle className="h-4 w-4 mt-0.5 shrink-0" />
                    <div>
                      <div className="font-medium">{t('documentDetail.noRepoTitle', 'No repository attached')}</div>
                      <p className="mt-1 text-amber-700/80 dark:text-amber-300/80">
                        {t('documentDetail.noRepoDesc', 'This document is not attached to a GitHub repository. Attach a repository so the AI can ground its regeneration in your source code and you can link specific entities.')}
                      </p>
                    </div>
                  </div>
                  <Button
                    size="sm"
                    onClick={() => void openAttachRepoDialog()}
                  >
                    <Plus className="mr-1 h-4 w-4" />
                    {t('documentDetail.attachRepoCta', 'Attach repository')}
                  </Button>
                </div>
              ) : links.length === 0 ? (
                <EmptyState
                  icon={<Link2 className="h-8 w-8" />}
                  title={t('documentDetail.noLinks', 'No links yet')}
                  description={t('documentDetail.noLinksDesc', 'Link code entities to track references between this document and source code. Linked entities will be used as AI grounding.')}
                />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('documentDetail.colEntityName', 'Entity')}</TableHead>
                      <TableHead>{t('documentDetail.colEntityFile', 'File')}</TableHead>
                      <TableHead>{t('documentDetail.colEntityKind', 'Type')}</TableHead>
                      <TableHead>{t('documentDetail.colLinkKind', 'Link kind')}</TableHead>
                      <TableHead>{t('documentDetail.colConfidence', 'Confidence')}</TableHead>
                      <TableHead>{t('documentDetail.colCreated', 'Linked at')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {links.map((l) => {
                      // Resolve codeEntityId → CodeEntity to show file/name
                      const ent = codeEntities.find((c) => c.id === l.codeEntityId);
                      return (
                        <TableRow key={l.id}>
                          <TableCell>
                            <code className="text-xs font-mono text-blue-600 dark:text-blue-400">
                              {ent ? ent.qualifiedName : l.codeEntityId}
                            </code>
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground font-mono max-w-[260px] truncate">
                            {ent ? `${ent.filePath}:${ent.startLine}-${ent.endLine}` : '—'}
                          </TableCell>
                          <TableCell>
                            {ent ? (
                              <Badge variant="muted" className="text-[10px]">{ent.entityType}</Badge>
                            ) : (
                              <span className="text-xs text-muted-foreground">—</span>
                            )}
                          </TableCell>
                          <TableCell><Badge variant="muted">{l.linkKind}</Badge></TableCell>
                          <TableCell>
                            {l.confidence !== null ? `${(l.confidence * 100).toFixed(0)}%` : '—'}
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {format(new Date(l.createdAt), 'MMM d, yyyy HH:mm')}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ── DRIFT TAB ── */}
        <TabsContent value="drift" className="space-y-4">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-base flex items-center gap-2">
                  <Shield className="h-4 w-4" /> Drift alerts
                </CardTitle>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => void handleTriggerDriftForDoc()}
                  disabled={!doc.repositoryId}
                  title={!doc.repositoryId ? 'Attach a repository first to analyze drift' : undefined}
                >
                  <Play className="mr-1 h-4 w-4" /> Analyze Drift
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              {driftScanResult && (
                <div className={`rounded-md border px-3 py-2 text-sm mb-3 ${driftScanResult.includes('complete') ? 'border-green-500/40 bg-green-500/10 text-green-700' : 'border-destructive/50 bg-destructive/10 text-destructive'}`}>
                  {driftScanning ? (
                    <span className="flex items-center gap-2"><Loader2 className="h-4 w-4 animate-spin" /> Scanning...</span>
                  ) : driftScanResult}
                </div>
              )}
              {driftAlerts.length === 0 ? (
                <EmptyState title={t('documentDetail.noOpenDrift')} description={t('documentDetail.noOpenDriftDesc')} />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Severity</TableHead>
                      <TableHead>Kind</TableHead>
                      <TableHead>Title</TableHead>
                      <TableHead>Detected</TableHead>
                      <TableHead>Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {driftAlerts.map((a) => (
                      <TableRow key={a.id}>
                        <TableCell>
                          <Badge
                            variant={
                              a.severity === 'CRITICAL' ? 'destructive'
                                : a.severity === 'HIGH' ? 'warning'
                                : a.severity === 'MEDIUM' ? 'info'
                                : 'muted'
                            }
                          >
                            {a.severity}
                          </Badge>
                        </TableCell>
                        <TableCell><Badge variant="muted">{a.driftKind}</Badge></TableCell>
                        <TableCell className="max-w-[250px] truncate">{a.title}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {format(new Date(a.detectedAt), 'MMM d, yyyy')}
                        </TableCell>
                        <TableCell>
                          <Dialog open={resolveAlertId === a.id} onOpenChange={(o) => { if (!o) setResolveAlertId(null); }}>
                            <DialogTrigger asChild>
                              <Button size="sm" variant="outline" onClick={() => setResolveAlertId(a.id)}>
                                Resolve
                              </Button>
                            </DialogTrigger>
                            <DialogContent>
                              <DialogHeader>
                                <DialogTitle>Resolve drift alert</DialogTitle>
                                <DialogDescription>{a.title}</DialogDescription>
                              </DialogHeader>
                              <div className="space-y-3">
                                <Select value={resolveResolution} onValueChange={setResolveResolution}>
                                  <SelectTrigger>
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    <SelectItem value="FIXED">{t('drift.resolutionFixed')}</SelectItem>
                                    <SelectItem value="ACCEPTED">{t('drift.resolutionAccepted')}</SelectItem>
                                    <SelectItem value="DISMISSED">{t('drift.resolutionDismissed')}</SelectItem>
                                    <SelectItem value="OPEN">{t('drift.resolutionOpen')}</SelectItem>
                                  </SelectContent>
                                </Select>
                              </div>
                              <DialogFooter>
                                <Button variant="outline" onClick={() => setResolveAlertId(null)}>Cancel</Button>
                                <Button onClick={() => void handleResolveDrift(a.id)}>Resolve</Button>
                              </DialogFooter>
                            </DialogContent>
                          </Dialog>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* Commit-to-GitHub dialog — placed at root so it works regardless of active tab */}
      <Dialog open={showCommitDialog} onOpenChange={(o) => { if (!o) { setShowCommitDialog(false); setCommitSuccess(null); } }}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              <Upload className="mr-2 inline h-4 w-4" />
              {t('documentDetail.commitDialogTitle', 'Commit document to GitHub')}
            </DialogTitle>
            <DialogDescription>
              {t('documentDetail.commitDialogDesc', 'This opens a new branch and a pull request against the repository\'s default branch. The file path defaults to docs/<slug>.md but you can change it.')}
            </DialogDescription>
          </DialogHeader>
          {commitSuccess ? (
            <div className="space-y-3">
              <div className="rounded-md border border-green-500/40 bg-green-500/10 p-4 text-sm text-green-700 dark:text-green-300">
                <div className="font-medium mb-1">
                  {t('documentDetail.commitOpened', 'Pull request opened')}
                </div>
                <a
                  href={commitSuccess.url}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-primary hover:underline break-all"
                >
                  {commitSuccess.url}
                  <ExternalLink className="h-3 w-3" />
                </a>
                <div className="text-xs text-muted-foreground mt-2">
                  {t('documentDetail.commitBranchLabel', { branch: commitSuccess.branch, defaultValue: `Branch: ${commitSuccess.branch}` })}
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => { setShowCommitDialog(false); setCommitSuccess(null); }}>
                  {t('common.close', 'Close')}
                </Button>
              </DialogFooter>
            </div>
          ) : (
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="commit-repository">{t('documentDetail.commitRepository', 'Repository')}</Label>
                <select
                  id="commit-repository"
                  className="flex h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm shadow-sm transition-colors file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
                  value={commitRepositoryId}
                  onChange={(e) => setCommitRepositoryId(e.target.value)}
                  disabled={commitReposLoading}
                >
                  {commitReposLoading ? (
                    <option value="">Loading repositories...</option>
                  ) : commitRepos.length === 0 ? (
                    <option value="">No repositories found</option>
                  ) : (
                    commitRepos.map((repo) => (
                      <option key={repo.id} value={repo.id}>
                        {repo.fullName} {repo.isPrivate ? '🔒' : '🌐'}
                      </option>
                    ))
                  )}
                </select>
                <p className="text-xs text-muted-foreground">
                  {t('documentDetail.commitRepositoryHint', 'Select the GitHub repository to commit to.')}
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="commit-file-path">{t('documentDetail.commitFilePath', 'File path')}</Label>
                <Input
                  id="commit-file-path"
                  value={commitFilePath}
                  onChange={(e) => setCommitFilePath(e.target.value)}
                  placeholder="docs/intro.md"
                />
                <p className="text-xs text-muted-foreground">
                  {t('documentDetail.commitFilePathHint', 'Path inside the repository. Will be created or overwritten on the new branch.')}
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="commit-branch">{t('documentDetail.commitBranch', 'Feature branch (optional)')}</Label>
                <Input
                  id="commit-branch"
                  value={commitBranch}
                  onChange={(e) => setCommitBranch(e.target.value)}
                  placeholder="livingdocs/docs/intro-1697123456"
                />
                <p className="text-xs text-muted-foreground">
                  {t('documentDetail.commitBranchHint', 'Leave blank to auto-generate from the file path.')}
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="commit-message">{t('documentDetail.commitMessageLabel', 'Commit message')}</Label>
                <Input
                  id="commit-message"
                  value={commitMessage}
                  onChange={(e) => setCommitMessage(e.target.value)}
                  placeholder="docs: update intro"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pr-title">{t('documentDetail.prTitleLabel', 'Pull request title')}</Label>
                <Input
                  id="pr-title"
                  value={prTitle}
                  onChange={(e) => setPrTitle(e.target.value)}
                  placeholder="docs: Update introduction"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pr-body">{t('documentDetail.prBodyLabel', 'Pull request description (optional)')}</Label>
                <Textarea
                  id="pr-body"
                  value={prBody}
                  onChange={(e) => setPrBody(e.target.value)}
                  rows={4}
                  placeholder="Why is this change needed?"
                />
              </div>
              {commitError && (
                <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive space-y-2">
                  <div>{commitError}</div>
                  {commitError.toLowerCase().includes('token') ||
                   commitError.toLowerCase().includes('reconnect') ||
                   commitError.toLowerCase().includes('access') ? (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={async () => {
                        try {
                          await githubApi.disconnect();
                        } catch {
                          // ignore — disconnect is best-effort
                        }
                        // Redirect directly to GitHub OAuth so the new token
                        // has scope=repo. Build the URL client-side to
                        // avoid needing the (possibly stale) JWT for the
                        // /oauth/authorize-url call.
                        const clientId = (import.meta.env.VITE_GITHUB_OAUTH_CLIENT_ID
                          || 'Ov23liHgJFSFryoJ34rS') as string;
                        const redirectUri = (import.meta.env.VITE_GITHUB_OAUTH_REDIRECT_URI
                          || 'http://localhost:8080/api/v1/github/oauth/callback') as string;
                        const scope = 'repo%20read%3Auser%20user%3Aemail%20workflow';
                        const state = (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
                          ? crypto.randomUUID()
                          : Math.random().toString(36).slice(2);
                        sessionStorage.setItem('github-oauth-state', state);
                        const url =
                          `https://github.com/login/oauth/authorize` +
                          `?client_id=${encodeURIComponent(clientId)}` +
                          `&redirect_uri=${encodeURIComponent(redirectUri)}` +
                          `&scope=${scope}` +
                          `&state=${encodeURIComponent(state)}` +
                          `&allow_signup=true`;
                        window.location.href = url;
                      }}
                    >
                      {t('documentDetail.reconnectGithub', 'Reconnect GitHub')}
                    </Button>
                  ) : null}
                </div>
              )}
              <DialogFooter>
                <Button variant="outline" onClick={() => setShowCommitDialog(false)} disabled={committing}>
                  {t('common.cancel', 'Cancel')}
                </Button>
                <Button onClick={() => void handleCommitToGithub()} disabled={committing}>
                  {committing ? (
                    <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('documentDetail.committing', 'Opening PR…')}</>
                  ) : (
                    <><Upload className="mr-1 h-4 w-4" /> {t('documentDetail.commitOpenPr', 'Open pull request')}</>
                  )}
                </Button>
              </DialogFooter>
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* Grounding evidence dialog */}
      {groundingFor && (
        <Dialog open={!!groundingFor} onOpenChange={() => setGroundingFor(null)}>
          <DialogContent className="max-w-2xl">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <Sparkles className="h-4 w-4" /> Grounding evidence
              </DialogTitle>
              <DialogDescription>
                {groundingLoading
                  ? 'Loading…'
                  : groundingFor.sources.length === 0
                    ? 'No grounding evidence is stored for this version. Older versions created before the grounding feature shipped do not have this data.'
                    : `The AI referenced ${groundingFor.sources.length} code entity/entities when generating this version.`}
              </DialogDescription>
            </DialogHeader>
            {!groundingLoading && groundingFor.sources.length > 0 && (
              <div className="max-h-96 overflow-auto space-y-2">
                {groundingFor.sources.map((s, i) => (
                  <div key={i} className="rounded border bg-muted/30 p-2">
                    <div className="flex items-center gap-2">
                      <Badge variant="info">{s.kind}</Badge>
                      <code className="text-xs font-mono text-blue-600 dark:text-blue-400 truncate">{s.qualifiedName}</code>
                    </div>
                    {s.file && (
                      <div className="mt-1 text-xs text-muted-foreground font-mono truncate">
                        {s.file}{s.lines && s.lines.length > 0 ? `:${s.lines[0]}-${s.lines[s.lines.length - 1]}` : ''}
                      </div>
                    )}
                    {s.signature && (
                      <pre className="mt-1 text-[11px] font-mono text-foreground/80 whitespace-pre-wrap break-words">
                        {s.signature}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            )}
            <DialogFooter>
              <DialogClose asChild><Button variant="outline">Close</Button></DialogClose>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {/* Preview dialog */}
      <Dialog open={previewOpen} onOpenChange={setPreviewOpen}>
        <DialogContent className="max-w-3xl max-h-[80vh]">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Sparkles className="h-4 w-4" /> {t('documentDetail.previewTitle', 'AI preview')}
            </DialogTitle>
            <DialogDescription>
              {previewing
                ? t('documentDetail.previewing', 'Previewing…')
                : t('documentDetail.previewDesc', 'This is a preview generated by the AI. No version will be persisted until you click "Generate with AI".')}
            </DialogDescription>
          </DialogHeader>
          {previewing ? (
            <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" /> {t('documentDetail.previewing', 'Previewing…')}
            </div>
          ) : (
            <>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Badge variant="info">{Math.round(previewConfidence * 100)}% confidence</Badge>
                <span>·</span>
                <span>template: {selectedTemplate}</span>
              </div>
              <pre className="font-mono text-xs whitespace-pre-wrap bg-muted/40 rounded p-3 overflow-auto max-h-96">
                {previewText || '(no content)'}
              </pre>
            </>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setPreviewOpen(false)}>
              {t('common.cancel', 'Cancel')}
            </Button>
            <Button onClick={() => { setPreviewOpen(false); void handleGenerateWithAI(); }} disabled={previewing || !previewText}>
              <Bot className="mr-1 h-4 w-4" /> {t('documentDetail.generateAi', 'Generate with AI')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Trigger commit dialog */}
      <Dialog open={!!commitFor} onOpenChange={() => setCommitFor(null)}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Bot className="h-4 w-4" /> Trigger commit
            </DialogTitle>
            <DialogDescription>
              {commitLoading
                ? 'Loading…'
                : commitFor
                  ? `Version v${commitFor.versionNumber} was auto-generated from the following commit.`
                  : 'No trigger commit information available.'}
            </DialogDescription>
          </DialogHeader>
          {commitFor && (
            <div className="space-y-2 text-sm">
              <div className="flex items-center gap-2">
                <span className="text-muted-foreground text-xs">SHA:</span>
                <code className="font-mono text-xs bg-muted px-1.5 py-0.5 rounded">{commitFor.commit.shortSha || '—'}</code>
              </div>
              {commitFor.commit.message && (
                <div>
                  <div className="text-xs text-muted-foreground mb-1">Message</div>
                  <pre className="text-xs whitespace-pre-wrap bg-muted/40 rounded p-2">{commitFor.commit.message}</pre>
                </div>
              )}
              {commitFor.commit.authorName && (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">Author:</span>
                  <span className="text-xs">{commitFor.commit.authorName}</span>
                </div>
              )}
              {commitFor.commit.authoredAt && (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground">Date:</span>
                  <span className="text-xs">{format(new Date(commitFor.commit.authoredAt), 'MMM d, yyyy HH:mm')}</span>
                </div>
              )}
              {commitFor.commit.url && (
                <a
                  href={commitFor.commit.url}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="text-xs text-blue-600 dark:text-blue-400 hover:underline inline-flex items-center gap-1"
                >
                  View on GitHub →
                </a>
              )}
            </div>
          )}
          <DialogFooter>
            <DialogClose asChild><Button variant="outline">Close</Button></DialogClose>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
