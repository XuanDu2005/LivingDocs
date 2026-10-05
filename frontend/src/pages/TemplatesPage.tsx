import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Pencil, Plus, Sparkles, Trash2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { templatesApi, DocTemplate, DocTemplateListResponse } from '../services/templates';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

const defaultBodyJson = JSON.stringify(
  { sections: [{ heading: 'Overview', placeholder: 'One paragraph summary.' }] },
  null,
  2,
);

export default function TemplatesPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();

  const [list, setList] = useState<DocTemplateListResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [editingTemplate, setEditingTemplate] = useState<DocTemplate | null>(null);
  const [name, setName] = useState<string>('');
  const [slug, setSlug] = useState<string>('');
  const [docType, setDocType] = useState<string>('MODULE_GUIDE');
  const [description, setDescription] = useState<string>('');
  const [bodyJson, setBodyJson] = useState<string>(defaultBodyJson);
  const [isDefault, setIsDefault] = useState<boolean>(false);
  const [busy, setBusy] = useState<boolean>(false);
  const [showForm, setShowForm] = useState<boolean>(false);

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await templatesApi.list(workspaceId);
      setList(res);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [workspaceId]);

  function openCreate() {
    setEditingTemplate(null);
    setName('');
    setSlug('');
    setDocType('MODULE_GUIDE');
    setDescription('');
    setBodyJson(defaultBodyJson);
    setIsDefault(false);
    setShowForm(true);
  }

  function openEdit(t: DocTemplate) {
    setEditingTemplate(t);
    setName(t.name);
    setSlug('');
    setDocType(t.docType);
    setDescription(t.description ?? '');
    setBodyJson(t.bodyJson);
    setIsDefault(t.isDefault);
    setShowForm(true);
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    try {
      if (editingTemplate) {
        await templatesApi.update(workspaceId, editingTemplate.id, {
          name,
          description: description || undefined,
          bodyJson,
          isDefault,
        });
      } else {
        await templatesApi.create(workspaceId, {
          name,
          slug,
          description: description || undefined,
          docType,
          bodyJson,
          isDefault,
        });
      }
      setShowForm(false);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(t: DocTemplate) {
    if (!workspaceId) return;
    if (!window.confirm(`Delete template "${t.name}"? This cannot be undone.`)) return;
    try {
      await templatesApi.remove(workspaceId, t.id);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Templates</h1>
          <p className="text-sm text-muted-foreground">
            Workspace and global documentation templates.
          </p>
        </div>
        <Button onClick={() => openCreate()}>
          <Plus className="mr-1 h-4 w-4" /> New template
        </Button>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading templates…" />}

      {/* Create / Edit dialog */}
      <Dialog open={showForm} onOpenChange={setShowForm}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editingTemplate ? 'Edit template' : 'New template'}
            </DialogTitle>
            <DialogDescription>
              {editingTemplate
                ? 'Update the workspace template. The docType cannot be changed.'
                : 'Create a new workspace template for this workspace.'}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="tmpl-name">Name <span className="text-destructive">*</span></Label>
              <Input
                id="tmpl-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                placeholder="My template"
              />
            </div>

            {!editingTemplate && (
              <div className="space-y-1.5">
                <Label htmlFor="tmpl-slug">Slug <span className="text-destructive">*</span></Label>
                <Input
                  id="tmpl-slug"
                  value={slug}
                  onChange={(e) => setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))}
                  required
                  placeholder="my-template"
                />
              </div>
            )}

            {!editingTemplate && (
              <div className="space-y-1.5">
                <Label htmlFor="tmpl-type">Document type</Label>
                <Input
                  id="tmpl-type"
                  value={docType}
                  onChange={(e) => setDocType(e.target.value)}
                  placeholder="MODULE_GUIDE"
                />
              </div>
            )}

            <div className="space-y-1.5">
              <Label htmlFor="tmpl-desc">Description</Label>
              <Input
                id="tmpl-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Brief description…"
              />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="tmpl-body">Body (JSON)</Label>
              <Textarea
                id="tmpl-body"
                value={bodyJson}
                onChange={(e) => setBodyJson(e.target.value)}
                rows={8}
                className="font-mono text-xs"
                required
              />
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="tmpl-default"
                checked={isDefault}
                onChange={(e) => setIsDefault(e.target.checked)}
                className="h-4 w-4 rounded border-input accent-primary"
              />
              <Label htmlFor="tmpl-default" className="text-sm font-normal cursor-pointer">
                Use as default for new documents of this type
              </Label>
            </div>

            <DialogFooter>
              <DialogClose asChild><Button type="button" variant="outline">Cancel</Button></DialogClose>
              <Button type="submit" disabled={busy}>
                {busy ? 'Saving…' : editingTemplate ? 'Save changes' : 'Create template'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {!loading && list && (
        <>
          {/* Workspace templates */}
          <div>
            <h2 className="text-lg font-semibold mb-3 flex items-center gap-2">
              <Sparkles className="h-4 w-4" /> Workspace templates
              <Badge variant="muted">{list.workspaceTemplates.length}</Badge>
            </h2>
            {list.workspaceTemplates.length === 0 ? (
              <Card>
                <CardContent className="pt-6">
                  <EmptyState
                    title="No workspace templates"
                    description="Create a template to scaffold new documents."
                    action={
                      <Button onClick={() => openCreate()}>
                        <Plus className="mr-1 h-4 w-4" /> New template
                      </Button>
                    }
                  />
                </CardContent>
              </Card>
            ) : (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {list.workspaceTemplates.map((t) => (
                  <Card key={t.id}>
                    <CardHeader className="pb-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <CardTitle className="text-sm truncate">{t.name}</CardTitle>
                          <CardDescription className="text-xs font-mono">{t.slug}</CardDescription>
                        </div>
                        <div className="flex gap-1 flex-shrink-0">
                          {t.isDefault && <Badge variant="success" className="text-xs">default</Badge>}
                        </div>
                      </div>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      <div className="flex gap-1.5 flex-wrap">
                        <Badge variant="muted" className="text-xs">{t.docType}</Badge>
                        <Badge variant="muted" className="text-xs">v{t.version}</Badge>
                      </div>
                      {t.description && (
                        <p className="text-xs text-muted-foreground">{t.description}</p>
                      )}
                      <p className="text-xs text-muted-foreground">
                        Updated {format(new Date(t.updatedAt), 'MMM d, yyyy')}
                      </p>
                      <div className="flex gap-2 pt-1">
                        <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => openEdit(t)}>
                          <Pencil className="mr-1 h-3 w-3" /> Edit
                        </Button>
                        <Button size="sm" variant="ghost" className="h-7 text-xs text-destructive hover:text-destructive" onClick={() => void handleDelete(t)}>
                          <Trash2 className="mr-1 h-3 w-3" /> Delete
                        </Button>
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>
            )}
          </div>

          {/* Global templates */}
          <div>
            <h2 className="text-lg font-semibold mb-3 flex items-center gap-2">
              <Sparkles className="h-4 w-4" /> Global library
              <Badge variant="muted">{list.globalTemplates.length}</Badge>
            </h2>
            {list.globalTemplates.length === 0 ? (
              <Card>
                <CardContent className="pt-6">
                  <EmptyState
                    title="No global templates"
                    description="Global templates will appear here once added by the platform."
                  />
                </CardContent>
              </Card>
            ) : (
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                {list.globalTemplates.map((t) => (
                  <Card key={t.id}>
                    <CardHeader className="pb-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <CardTitle className="text-sm truncate">{t.name}</CardTitle>
                          <CardDescription className="text-xs font-mono">{t.slug}</CardDescription>
                        </div>
                        <div className="flex gap-1 flex-shrink-0">
                          {t.isDefault && <Badge variant="success" className="text-xs">default</Badge>}
                        </div>
                      </div>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      <div className="flex gap-1.5 flex-wrap">
                        <Badge variant="muted" className="text-xs">{t.docType}</Badge>
                        <Badge variant="muted" className="text-xs">v{t.version}</Badge>
                      </div>
                      {t.description && (
                        <p className="text-xs text-muted-foreground">{t.description}</p>
                      )}
                      <p className="text-xs text-muted-foreground">
                        Updated {format(new Date(t.updatedAt), 'MMM d, yyyy')}
                      </p>
                      <p className="text-xs text-muted-foreground italic">Read-only</p>
                    </CardContent>
                  </Card>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
