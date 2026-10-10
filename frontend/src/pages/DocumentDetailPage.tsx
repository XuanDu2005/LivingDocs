import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Bot, Check, Eye, History, Link2, Plus, RotateCcw, Shield, Upload } from 'lucide-react';
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
import { describeError } from '../services/auth';
import { format } from 'date-fns';
import RegenerateDocButton from '../components/RegenerateDocButton';

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
  const [tab, setTab] = useState<string>('content');

  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Link entity dialog
  const [showLinkEntity, setShowLinkEntity] = useState<boolean>(false);
  const [codeEntities, setCodeEntities] = useState<CodeEntity[]>([]);
  const [selectedEntityId, setSelectedEntityId] = useState<string>('');
  const [linkingEntity, setLinkingEntity] = useState<boolean>(false);

  // Resolve drift dialog
  const [resolveAlertId, setResolveAlertId] = useState<string | null>(null);
  const [resolveResolution, setResolveResolution] = useState<string>('FIXED');

  // New version dialog
  const [showNewVersion, setShowNewVersion] = useState<boolean>(false);
  const [newBody, setNewBody] = useState<string>('');
  const [newSummary, setNewSummary] = useState<string>('');
  const [creatingVersion, setCreatingVersion] = useState<boolean>(false);

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

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (tab === 'drift') void loadDrift();
    if (tab === 'links') void loadLinks();
  }, [tab, loadDrift, loadLinks]);

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
      await aiApi.generate(workspaceId, documentId, []);
      await load();
    } catch (err) {
      setGenError(describeError(err));
    } finally {
      setGenerating(false);
    }
  }

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

  async function handleLoadEntities() {
    if (!workspaceId || !doc?.repositoryId) return;
    try {
      const entities = await codeApi.listEntities(workspaceId, doc.repositoryId);
      setCodeEntities(entities);
    } catch (err) {
      setError(describeError(err));
    }
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
        <div className="flex gap-2 flex-shrink-0">
          <Button size="sm" onClick={() => void handleGenerateWithAI()} disabled={generating}>
            <Bot className="mr-1 h-4 w-4" /> {generating ? t('documentDetail.generating') : t('documentDetail.generateAi')}
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
                  <CardTitle className="text-sm">
                    {selectedVersion ? `v${selectedVersion.versionNumber} body` : t('documentDetail.documentBody')}
                  </CardTitle>
                  <div className="flex items-center gap-2">
                    {saving && <span className="text-xs text-muted-foreground animate-pulse">Saving…</span>}
                    {saveSuccess && <span className="text-xs text-green-600 flex items-center gap-1"><Check className="h-3 w-3" /> Saved</span>}
                    <Button size="sm" variant="outline" onClick={() => void handleViewDiff()} disabled={diffLoading}>
                      <Eye className="mr-1 h-4 w-4" /> View diff
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
                <Dialog open={showLinkEntity} onOpenChange={setShowLinkEntity}>
                  <DialogTrigger asChild>
                    <Button size="sm" variant="outline" disabled={!doc?.repositoryId} onClick={() => void handleLoadEntities()}>
                      <Link2 className="mr-1 h-4 w-4" /> Link entity
                    </Button>
                  </DialogTrigger>
                  <DialogContent>
                    <DialogHeader>
                      <DialogTitle>Link code entity</DialogTitle>
                      <DialogDescription>
                        Select a code entity to link to this document.
                      </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-3">
                      {codeEntities.length === 0 ? (
                        <p className="text-sm text-muted-foreground">
                          No code entities found for this repository.
                        </p>
                      ) : (
                        <Select value={selectedEntityId} onValueChange={setSelectedEntityId}>
                          <SelectTrigger>
                            <SelectValue placeholder={t('documentDetail.selectEntity')} />
                          </SelectTrigger>
                          <SelectContent>
                            {codeEntities.map((e) => (
                              <SelectItem key={e.id} value={e.id}>
                                [{e.entityType}] {e.qualifiedName}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      )}
                    </div>
                    <DialogFooter>
                      <Button variant="outline" onClick={() => setShowLinkEntity(false)}>Cancel</Button>
                      <Button onClick={() => void handleLinkEntity()} disabled={!selectedEntityId || linkingEntity}>
                        {linkingEntity ? t('documentDetail.linking') : t('documentDetail.linkEntity')}
                      </Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
              </div>
            </CardHeader>
            <CardContent>
              {links.length === 0 ? (
                <EmptyState
                  title={t('documentDetail.noLinks')}
                  description={t('documentDetail.noLinksDesc')}
                />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Entity</TableHead>
                      <TableHead>Kind</TableHead>
                      <TableHead>Confidence</TableHead>
                      <TableHead>Created</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {links.map((l) => (
                      <TableRow key={l.id}>
                        <TableCell className="font-mono text-xs">{l.codeEntityId}</TableCell>
                        <TableCell><Badge variant="muted">{l.linkKind}</Badge></TableCell>
                        <TableCell>{l.confidence !== null ? `${(l.confidence * 100).toFixed(0)}%` : '—'}</TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {format(new Date(l.createdAt), 'MMM d, yyyy')}
                        </TableCell>
                      </TableRow>
                    ))}
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
              <CardTitle className="text-base flex items-center gap-2">
                <Shield className="h-4 w-4" /> Drift alerts
              </CardTitle>
            </CardHeader>
            <CardContent>
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
    </div>
  );
}
