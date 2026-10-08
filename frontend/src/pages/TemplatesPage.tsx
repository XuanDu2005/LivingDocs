import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Code2, Eye, Pencil, Plus, Sparkles, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Tabs, TabsList, TabsTrigger,
} from '../components/ui/tabs';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { templatesApi, DocTemplate, DocTemplateListResponse } from '../services/templates';
import { templateSchemaApi } from '../services/templateSchema';
import { describeError } from '../services/auth';
import { VisualTemplateBuilder } from '../components/VisualTemplateBuilder';
import {
  TemplateBody,
  TemplateSchemaResponse,
  bodyToJson,
  jsonToBody,
  EMPTY_BODY,
  DocTypeInfo,
} from '../types/templateBody';
import { format } from 'date-fns';

type EditMode = 'visual' | 'json' | 'preview';
type OutputFormat = 'MARKDOWN' | 'HTML' | 'PDF';

const OUTPUT_FORMATS: OutputFormat[] = ['MARKDOWN', 'HTML', 'PDF'];

export default function TemplatesPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [list, setList] = useState<DocTemplateListResponse | null>(null);
  const [schema, setSchema] = useState<TemplateSchemaResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [editingTemplate, setEditingTemplate] = useState<DocTemplate | null>(null);
  const [name, setName] = useState<string>('');
  const [slug, setSlug] = useState<string>('');
  const [docType, setDocType] = useState<string>('MODULE_GUIDE');
  const [description, setDescription] = useState<string>('');
  const [body, setBody] = useState<TemplateBody>(EMPTY_BODY);
  const [bodyJsonText, setBodyJsonText] = useState<string>('');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [isDefault, setIsDefault] = useState<boolean>(false);
  const [outputFormat, setOutputFormat] = useState<OutputFormat>('MARKDOWN');
  const [autoOnCommit, setAutoOnCommit] = useState<boolean>(false);
  const [autoOnPr, setAutoOnPr] = useState<boolean>(false);
  const [autoOnMerge, setAutoOnMerge] = useState<boolean>(false);
  const [busy, setBusy] = useState<boolean>(false);
  const [showForm, setShowForm] = useState<boolean>(false);
  const [editMode, setEditMode] = useState<EditMode>('visual');

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const [res, sch] = await Promise.all([
        templatesApi.list(workspaceId),
        templateSchemaApi.get().catch(() => null),
      ]);
      setList(res);
      if (sch) setSchema(sch);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [workspaceId]);

  // Re-derive JSON when the body changes through the visual builder.
  useEffect(() => {
    setBodyJsonText(bodyToJson(body));
    setJsonError(null);
  }, [body]);

  // Re-parse the body when the JSON text changes through the JSON editor.
  useEffect(() => {
    if (editMode !== 'json') return;
    try {
      setBody(jsonToBody(bodyJsonText));
      setJsonError(null);
    } catch (e) {
      setJsonError(describeError(e));
    }
  }, [bodyJsonText, editMode]);

  function applyBodyFromTemplate(t: DocTemplate) {
    try {
      const parsed = jsonToBody(t.bodyJson);
      setBody(parsed);
      setBodyJsonText(t.bodyJson);
    } catch {
      setBody(EMPTY_BODY);
      setBodyJsonText(t.bodyJson);
    }
  }

  function openCreate() {
    setEditingTemplate(null);
    setName('');
    setSlug('');
    setDocType('MODULE_GUIDE');
    setDescription('');
    setIsDefault(false);
    setOutputFormat('MARKDOWN');
    setAutoOnCommit(false);
    setAutoOnPr(false);
    setAutoOnMerge(false);
    const seed = schema?.sample ?? EMPTY_BODY;
    setBody({
      version: 1,
      titleHint: seed.titleHint ?? '',
      sections: seed.sections.map((s) => ({ ...s, placeholders: s.placeholders.map((p) => ({ ...p })) })),
      variables: seed.variables?.map((v) => ({ ...v })) ?? [],
    });
    setBodyJsonText(bodyToJson(body));
    setEditMode('visual');
    setShowForm(true);
  }

  function openEdit(t: DocTemplate) {
    setEditingTemplate(t);
    setName(t.name);
    setSlug('');
    setDocType(t.docType);
    setDescription(t.description ?? '');
    setIsDefault(t.isDefault);
    setOutputFormat((t.outputFormat as OutputFormat | null | undefined) ?? 'MARKDOWN');
    setAutoOnCommit(Boolean(t.autoGenerateOnCommit));
    setAutoOnPr(Boolean(t.autoGenerateOnPr));
    setAutoOnMerge(Boolean(t.autoGenerateOnMerge));
    applyBodyFromTemplate(t);
    setEditMode('visual');
    setShowForm(true);
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    if (jsonError) {
      setError(`JSON error: ${jsonError}`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const payload = {
        name,
        description: description || undefined,
        bodyJson: bodyToJson(body),
        outputFormat,
        autoGenerateOnCommit: autoOnCommit,
        autoGenerateOnPr: autoOnPr,
        autoGenerateOnMerge: autoOnMerge,
        isDefault,
      };
      if (editingTemplate) {
        await templatesApi.update(workspaceId, editingTemplate.id, payload);
      } else {
        await templatesApi.create(workspaceId, {
          ...payload,
          slug,
          docType,
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
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">{t('templates.title')}</h1>
          <p className="text-sm text-muted-foreground">
            {t('templates.subtitle')}
          </p>
        </div>
        <Button onClick={() => openCreate()}>
          <Plus className="mr-1 h-4 w-4" /> {t('templates.newCta')}
        </Button>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading templates…" />}

      {/* Create / Edit dialog */}
      <Dialog open={showForm} onOpenChange={setShowForm}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editingTemplate ? t('templates.dialogEdit') : t('templates.dialogNew')}
            </DialogTitle>
            <DialogDescription>
              {editingTemplate ? t('templates.dialogEditDesc') : t('templates.dialogNewDesc')}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="tmpl-name">{t('templates.fieldName')} <span className="text-destructive">*</span></Label>
                <Input
                  id="tmpl-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                  placeholder={t('templates.fieldName')}
                />
              </div>
              {!editingTemplate ? (
                <div className="space-y-1.5">
                  <Label htmlFor="tmpl-slug">{t('templates.fieldSlug')} <span className="text-destructive">*</span></Label>
                  <Input
                    id="tmpl-slug"
                    value={slug}
                    onChange={(e) => setSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''))}
                    required
                    placeholder="my-template"
                  />
                </div>
              ) : <div />}
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              {!editingTemplate && (
                <div className="space-y-1.5">
                  <Label htmlFor="tmpl-type">{t('templates.fieldDocType')}</Label>
                  <Select value={docType} onValueChange={setDocType}>
                    <SelectTrigger id="tmpl-type">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {(schema?.docTypes ?? [{ value: docType, label: docType, description: '' }]).map((tt: DocTypeInfo) => (
                        <SelectItem key={tt.value} value={tt.value} title={tt.description}>
                          {tt.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}
              <div className="space-y-1.5">
                <Label htmlFor="tmpl-desc">{t('templates.fieldDescription')}</Label>
                <Input
                  id="tmpl-desc"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder={t('templates.fieldDescription')}
                />
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="tmpl-output">{t('templates.outputFormat')}</Label>
                <Select
                  value={outputFormat}
                  onValueChange={(v) => setOutputFormat(v as OutputFormat)}
                >
                  <SelectTrigger id="tmpl-output"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {OUTPUT_FORMATS.map((f) => (
                      <SelectItem key={f} value={f}>
                        {t(`templates.outputFormat_${f}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-2 rounded-md border bg-muted/30 p-3">
              <div className="text-sm font-medium">{t('templates.autoTriggers')}</div>
              <p className="text-xs text-muted-foreground">{t('templates.autoTriggersDesc')}</p>
              <div className="space-y-1.5">
                {[
                  { field: 'autoOnCommit' as const, label: t('templates.autoOnCommit'), value: autoOnCommit, set: setAutoOnCommit },
                  { field: 'autoOnPr' as const, label: t('templates.autoOnPr'), value: autoOnPr, set: setAutoOnPr },
                  { field: 'autoOnMerge' as const, label: t('templates.autoOnMerge'), value: autoOnMerge, set: setAutoOnMerge },
                ].map((opt) => (
                  <label key={opt.field} className="flex items-center gap-2 text-sm cursor-pointer">
                    <input
                      type="checkbox"
                      checked={opt.value}
                      onChange={(e) => opt.set(e.target.checked)}
                      className="h-4 w-4 rounded border-input accent-primary"
                      aria-label={opt.label}
                    />
                    <span>{opt.label}</span>
                  </label>
                ))}
              </div>
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <Label className="text-sm font-semibold">Body</Label>
                <Tabs value={editMode} onValueChange={(v) => setEditMode(v as EditMode)}>
                  <TabsList>
                    <TabsTrigger value="visual">
                      <Sparkles className="mr-1 h-3 w-3" /> {t('templates.tabVisual')}
                    </TabsTrigger>
                    <TabsTrigger value="json">
                      <Code2 className="mr-1 h-3 w-3" /> {t('templates.tabJson')}
                    </TabsTrigger>
                    <TabsTrigger value="preview">
                      <Eye className="mr-1 h-3 w-3" /> {t('templates.tabPreview')}
                    </TabsTrigger>
                  </TabsList>
                </Tabs>
              </div>

              {editMode === 'visual' && (
                <VisualTemplateBuilder body={body} onChange={setBody} />
              )}

              {editMode === 'json' && (
                <div className="space-y-1.5">
                  <Textarea
                    value={bodyJsonText}
                    onChange={(e) => setBodyJsonText(e.target.value)}
                    rows={20}
                    className="font-mono text-xs"
                  />
                  {jsonError && (
                    <p className="text-xs text-destructive">
                      {t('templates.jsonError', { message: jsonError })}
                    </p>
                  )}
                </div>
              )}

              {editMode === 'preview' && (
                <div className="rounded-md border bg-muted/30 p-4">
                  <pre className="whitespace-pre-wrap text-xs font-mono leading-relaxed">
                    {renderPreview(body)}
                  </pre>
                </div>
              )}
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
                {t('templates.fieldIsDefault')}
              </Label>
            </div>

            <DialogFooter>
              <DialogClose asChild><Button type="button" variant="outline">{t('common.cancel')}</Button></DialogClose>
              <Button type="submit" disabled={busy || Boolean(jsonError)}>
                {busy ? t('common.actions.saving') :
                  editingTemplate ? t('common.update') : t('common.create')}
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
                {list.workspaceTemplates.map((tmpl) => (
                  <Card key={tmpl.id}>
                    <CardHeader className="pb-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <CardTitle className="text-sm truncate">{tmpl.name}</CardTitle>
                          <CardDescription className="text-xs font-mono">{tmpl.slug}</CardDescription>
                        </div>
                        <div className="flex gap-1 flex-shrink-0">
                          {tmpl.isDefault && <Badge variant="success" className="text-xs">default</Badge>}
                          {(tmpl.autoGenerateOnCommit || tmpl.autoGenerateOnPr || tmpl.autoGenerateOnMerge) && (
                            <Badge variant="info" className="text-xs">
                              {t('templates.autoTriggerBadge')}
                            </Badge>
                          )}
                        </div>
                      </div>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      <div className="flex gap-1.5 flex-wrap">
                        <Badge variant="muted" className="text-xs">{tmpl.docType}</Badge>
                        <Badge variant="muted" className="text-xs">v{tmpl.version}</Badge>
                      </div>
                      {tmpl.description && (
                        <p className="text-xs text-muted-foreground">{tmpl.description}</p>
                      )}
                      <p className="text-xs text-muted-foreground">
                        Updated {format(new Date(tmpl.updatedAt), 'MMM d, yyyy')}
                      </p>
                      <div className="flex gap-2 pt-1">
                        <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => openEdit(tmpl)}>
                          <Pencil className="mr-1 h-3 w-3" /> Edit
                        </Button>
                        <Button size="sm" variant="ghost" className="h-7 text-xs text-destructive hover:text-destructive" onClick={() => void handleDelete(tmpl)}>
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
                {list.globalTemplates.map((tmpl) => (
                  <Card key={tmpl.id}>
                    <CardHeader className="pb-2">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <CardTitle className="text-sm truncate">{tmpl.name}</CardTitle>
                          <CardDescription className="text-xs font-mono">{tmpl.slug}</CardDescription>
                        </div>
                        <div className="flex gap-1 flex-shrink-0">
                          {tmpl.isDefault && <Badge variant="success" className="text-xs">default</Badge>}
                          {(tmpl.autoGenerateOnCommit || tmpl.autoGenerateOnPr || tmpl.autoGenerateOnMerge) && (
                            <Badge variant="info" className="text-xs">
                              {t('templates.autoTriggerBadge')}
                            </Badge>
                          )}
                        </div>
                      </div>
                    </CardHeader>
                    <CardContent className="space-y-2">
                      <div className="flex gap-1.5 flex-wrap">
                        <Badge variant="muted" className="text-xs">{tmpl.docType}</Badge>
                        <Badge variant="muted" className="text-xs">v{tmpl.version}</Badge>
                      </div>
                      {tmpl.description && (
                        <p className="text-xs text-muted-foreground">{tmpl.description}</p>
                      )}
                      <p className="text-xs text-muted-foreground">
                        Updated {format(new Date(tmpl.updatedAt), 'MMM d, yyyy')}
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

function renderPreview(body: TemplateBody): string {
  const out: string[] = [];
  if (body.titleHint) out.push(`# ${body.titleHint}\n`);
  for (const sec of body.sections) {
    const level = sec.level || 2;
    out.push(`${'#'.repeat(Math.max(1, Math.min(6, level)))} ${sec.heading}\n`);
    for (const ph of sec.placeholders) {
      const req = ph.required ? ' (required)' : '';
      const w = ph.maxWords ? ` ≤ ${ph.maxWords}w` : '';
      const bind = ph.binding ? ` [${ph.binding}]` : '';
      out.push(`- [${ph.key}]${bind}${req}${w} — ${ph.prompt}`);
    }
    out.push('');
  }
  return out.join('\n');
}
