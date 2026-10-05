import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FilePlus } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { ErrorState } from '../components/ui/states';
import { documentsApi } from '../services/documents';
import { templatesApi } from '../services/templates';
import { githubApi } from '../services/github';
import { describeError } from '../services/auth';

export default function NewDocumentPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const navigate = useNavigate();

  const [title, setTitle] = useState<string>('');
  const [slug, setSlug] = useState<string>('');
  const [slugEdited, setSlugEdited] = useState<boolean>(false);
  const [summary, setSummary] = useState<string>('');
  const [docType, setDocType] = useState<string>('');
  const [templateId, setTemplateId] = useState<string>('');
  const [repositoryId, setRepositoryId] = useState<string>('');
  const [autoUpdateEnabled, setAutoUpdateEnabled] = useState<boolean>(false);
  const [initialBody, setInitialBody] = useState<string>('# Overview\n\n');

  const [templates, setTemplates] = useState<{ workspaceTemplates: { id: string; name: string }[]; globalTemplates: { id: string; name: string }[] }>({ workspaceTemplates: [], globalTemplates: [] });
  const [repositories, setRepositories] = useState<{ id: string; fullName: string }[]>([]);

  const [loadingTemplates, setLoadingTemplates] = useState<boolean>(false);
  const [loadingRepos, setLoadingRepos] = useState<boolean>(false);
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const loadTemplates = useCallback(async () => {
    if (!workspaceId) return;
    setLoadingTemplates(true);
    try {
      const res = await templatesApi.list(workspaceId);
      setTemplates(res);
    } catch {
      // non-critical
    } finally {
      setLoadingTemplates(false);
    }
  }, [workspaceId]);

  const loadRepos = useCallback(async () => {
    if (!workspaceId) return;
    setLoadingRepos(true);
    try {
      const repos = await githubApi.listRepositories(workspaceId);
      setRepositories(repos);
    } catch {
      // non-critical
    } finally {
      setLoadingRepos(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    void loadTemplates();
    void loadRepos();
  }, [loadTemplates, loadRepos]);

  function handleTitleChange(value: string) {
    setTitle(value);
    if (!slugEdited) {
      setSlug(
        value
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/(^-|-$)/g, ''),
      );
    }
  }

  function handleSlugChange(value: string) {
    setSlugEdited(true);
    // Enforce pattern: lowercase, alphanum and hyphens, must start with alphanum
    const cleaned = value.toLowerCase().replace(/[^a-z0-9-]/g, '');
    setSlug(cleaned.replace(/^-/, ''));
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    setSubmitting(true);
    setError(null);
    try {
      const created = await documentsApi.create(workspaceId, {
        title,
        slug,
        docType: docType || 'CUSTOM',
        summary: summary || undefined,
        templateId: templateId || undefined,
        repositoryId: repositoryId || undefined,
        autoUpdateEnabled,
        initialBody,
      });
      navigate(`/workspaces/${workspaceId}/documents/${created.id}`, { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (!workspaceId) {
    return (
      <div className="p-6">
        <p className="text-destructive">Missing workspace id.</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}/documents`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to documents
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">New document</h1>
        <p className="text-muted-foreground text-sm mt-1">
          Fill in the metadata and the initial body. You can publish once you have reviewed it.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <FilePlus className="h-4 w-4" /> Document details
          </CardTitle>
          <CardDescription>All fields except the initial body are optional and can be changed later.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            {/* Title */}
            <div className="space-y-1.5">
              <Label htmlFor="title">Title <span className="text-destructive">*</span></Label>
              <Input
                id="title"
                type="text"
                value={title}
                onChange={(e) => handleTitleChange(e.target.value)}
                placeholder="My awesome document"
                required
                maxLength={120}
              />
            </div>

            {/* Slug */}
            <div className="space-y-1.5">
              <Label htmlFor="slug">Slug</Label>
              <Input
                id="slug"
                type="text"
                value={slug}
                onChange={(e) => handleSlugChange(e.target.value)}
                placeholder="my-awesome-document"
                pattern="^[a-z0-9][a-z0-9-]*$"
                title="Lowercase letters, numbers, and hyphens. Must start with a letter or number."
              />
              <p className="text-xs text-muted-foreground">
                Lowercase letters, numbers, and hyphens. Auto-generated from title.
              </p>
            </div>

            {/* Summary */}
            <div className="space-y-1.5">
              <Label htmlFor="summary">Summary</Label>
              <Textarea
                id="summary"
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                placeholder="Brief description of this document…"
                maxLength={500}
                rows={2}
              />
            </div>

            {/* docType */}
            <div className="space-y-1.5">
              <Label htmlFor="docType">Document type</Label>
              <Input
                id="docType"
                type="text"
                value={docType}
                onChange={(e) => setDocType(e.target.value)}
                placeholder="e.g. guide, api, tutorial"
              />
            </div>

            {/* Template */}
            <div className="space-y-1.5">
              <Label htmlFor="template">Template</Label>
              {loadingTemplates ? (
                <p className="text-sm text-muted-foreground">Loading templates…</p>
              ) : (
                <Select value={templateId} onValueChange={setTemplateId}>
                  <SelectTrigger id="template">
                    <SelectValue placeholder="— None —" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="">— None —</SelectItem>
                    {templates.workspaceTemplates.length > 0 && (
                      <>
                        <div className="px-2 py-1.5 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                          Workspace templates
                        </div>
                        {templates.workspaceTemplates.map((t) => (
                          <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>
                        ))}
                      </>
                    )}
                    {templates.globalTemplates.length > 0 && (
                      <>
                        <div className="px-2 py-1.5 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                          Global library
                        </div>
                        {templates.globalTemplates.map((t) => (
                          <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>
                        ))}
                      </>
                    )}
                  </SelectContent>
                </Select>
              )}
            </div>

            {/* Repository */}
            <div className="space-y-1.5">
              <Label htmlFor="repository">Repository</Label>
              {loadingRepos ? (
                <p className="text-sm text-muted-foreground">Loading repositories…</p>
              ) : (
                <Select value={repositoryId} onValueChange={setRepositoryId}>
                  <SelectTrigger id="repository">
                    <SelectValue placeholder="— None —" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="">— None —</SelectItem>
                    {repositories.map((r) => (
                      <SelectItem key={r.id} value={r.id}>{r.fullName}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </div>

            {/* Auto-update */}
            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="autoUpdate"
                checked={autoUpdateEnabled}
                onChange={(e) => setAutoUpdateEnabled(e.target.checked)}
                className="h-4 w-4 rounded border-input accent-primary"
              />
              <Label htmlFor="autoUpdate" className="text-sm font-normal cursor-pointer">
                Automatically update when source code changes
              </Label>
            </div>

            {/* Initial body */}
            <div className="space-y-1.5">
              <Label htmlFor="initialBody">Initial body <span className="text-destructive">*</span></Label>
              <Textarea
                id="initialBody"
                value={initialBody}
                onChange={(e) => setInitialBody(e.target.value)}
                placeholder="Initial markdown content…"
                rows={10}
                required
                className="font-mono text-sm"
              />
            </div>

            {error && <ErrorState message={error} />}

            <div className="flex gap-2">
              <Button type="submit" disabled={submitting}>
                {submitting ? 'Creating…' : 'Create document'}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => navigate(`/workspaces/${workspaceId}/documents`)}
              >
                Cancel
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
