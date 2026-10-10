import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  CheckCircle2,
  FileText,
  Loader2,
  Pencil,
  Plus,
  Save,
  Star,
  Trash2,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Switch } from '../components/ui/switch';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  AdminCreateTemplatePayload,
  AdminDocTemplate,
  AdminUpdateTemplatePayload,
  adminTemplatesApi,
} from '../services/adminTemplates';

const DOC_TYPES: { value: string; label: string }[] = [
  { value: 'MODULE_GUIDE', label: 'Module guide' },
  { value: 'API_REFERENCE', label: 'API reference' },
  { value: 'README', label: 'README' },
  { value: 'ARCHITECTURE', label: 'Architecture' },
  { value: 'ADR', label: 'ADR' },
  { value: 'CHANGELOG', label: 'Changelog' },
  { value: 'RUNBOOK', label: 'Runbook' },
  { value: 'TUTORIAL', label: 'Tutorial' },
  { value: 'CUSTOM', label: 'Custom' },
];

const OUTPUT_FORMATS = ['MARKDOWN', 'HTML', 'PDF'] as const;
type OutputFormat = typeof OUTPUT_FORMATS[number];

const SAMPLE_BODY = JSON.stringify({
  version: 1,
  titleHint: 'Sample template — {{moduleName}}',
  sections: [
    {
      id: 'overview',
      heading: 'Overview',
      level: 2,
      placeholders: [
        { key: 'summary', prompt: 'A one-paragraph summary.', required: true, maxWords: 80 },
      ],
    },
  ],
  variables: [{ key: 'moduleName', default: 'core', description: 'Module name.' }],
}, null, 2);

interface FormState {
  name: string;
  slug: string;
  description: string;
  docType: string;
  bodyJson: string;
  outputFormat: OutputFormat;
  autoOnCommit: boolean;
  autoOnPr: boolean;
  autoOnMerge: boolean;
  isDefault: boolean;
}

const EMPTY_FORM: FormState = {
  name: '',
  slug: '',
  description: '',
  docType: 'MODULE_GUIDE',
  bodyJson: SAMPLE_BODY,
  outputFormat: 'MARKDOWN',
  autoOnCommit: false,
  autoOnPr: false,
  autoOnMerge: false,
  isDefault: false,
};

/**
 * Admin console for the platform-wide template library.
 *
 * <p>Each global template is the default that every new workspace
 * inherits. Administrators can create, edit, version, delete, and
 * promote the "default per docType" here.
 */
export default function AdminTemplatesPage() {
  const { t } = useTranslation();
  const [globals, setGlobals] = useState<AdminDocTemplate[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState<boolean>(false);
  const [editing, setEditing] = useState<AdminDocTemplate | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [saving, setSaving] = useState<boolean>(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const res = await adminTemplatesApi.list();
      // Deduplicate by slug — the table only shows the latest version.
      const byLatest = new Map<string, AdminDocTemplate>();
      for (const t of res.globalTemplates) {
        const existing = byLatest.get(t.slug);
        if (!existing || existing.version < t.version) byLatest.set(t.slug, t);
      }
      setGlobals(Array.from(byLatest.values()).sort((a, b) => {
        if (a.docType === b.docType) return a.name.localeCompare(b.name);
        return a.docType.localeCompare(b.docType);
      }));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormError(null);
    setFormOpen(true);
  }

  function openEdit(t: AdminDocTemplate) {
    setEditing(t);
    setForm({
      name: t.name,
      slug: t.slug,
      description: t.description ?? '',
      docType: t.docType ?? 'CUSTOM',
      bodyJson: t.bodyJson,
      outputFormat: (t.outputFormat ?? 'MARKDOWN') as OutputFormat,
      autoOnCommit: !!t.autoGenerateOnCommit,
      autoOnPr: !!t.autoGenerateOnPr,
      autoOnMerge: !!t.autoGenerateOnMerge,
      isDefault: t.isDefault,
    });
    setFormError(null);
    setFormOpen(true);
  }

  async function onSubmit() {
    setFormError(null);
    if (!form.name.trim() || (!editing && !form.slug.trim())) {
      setFormError('Name and slug are required.');
      return;
    }
    // Validate JSON body.
    try { JSON.parse(form.bodyJson); }
    catch (e) {
      setFormError('Template body is not valid JSON: ' + describeError(e));
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        const payload: AdminUpdateTemplatePayload = {
          name: form.name.trim(),
          description: form.description.trim() || undefined,
          bodyJson: form.bodyJson,
          outputFormat: form.outputFormat,
          autoGenerateOnCommit: form.autoOnCommit,
          autoGenerateOnPr: form.autoOnPr,
          autoGenerateOnMerge: form.autoOnMerge,
          isDefault: form.isDefault,
        };
        await adminTemplatesApi.update(editing.id, payload);
      } else {
        const payload: AdminCreateTemplatePayload = {
          name: form.name.trim(),
          slug: form.slug.trim().toLowerCase().replace(/[^a-z0-9-]/g, '-'),
          description: form.description.trim() || undefined,
          docType: form.docType,
          bodyJson: form.bodyJson,
          outputFormat: form.outputFormat,
          autoGenerateOnCommit: form.autoOnCommit,
          autoGenerateOnPr: form.autoOnPr,
          autoGenerateOnMerge: form.autoOnMerge,
          isDefault: form.isDefault,
        };
        await adminTemplatesApi.create(payload);
      }
      setFormOpen(false);
      await load();
    } catch (err) {
      setFormError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(t: AdminDocTemplate) {
    if (!confirm(`Delete global template "${t.name}" (v${t.version})? This removes every version for slug "${t.slug}".`)) return;
    try {
      await adminTemplatesApi.remove(t.id);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onSetDefault(t: AdminDocTemplate) {
    try {
      await adminTemplatesApi.setDefault(t.id);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  const defaultsByType = useMemo(() => {
    const m = new Map<string, AdminDocTemplate>();
    for (const t of globals) {
      if (t.isDefault && !m.has(t.docType)) m.set(t.docType, t);
    }
    return m;
  }, [globals]);

  if (loading) return <LoadingState message={t('adminTemplates.loading')} />;
  if (error && globals.length === 0) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight flex items-center gap-2">
          <FileText className="h-5 w-5" /> {t('adminTemplates.title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('adminTemplates.subtitle')}</p>
      </div>

      {error && <ErrorState message={error} />}

      <div className="flex justify-end">
        <Button onClick={openCreate}>
          <Plus className="mr-1 h-4 w-4" /> {t('adminTemplates.newCta')}
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('adminTemplates.catalogue')}</CardTitle>
          <CardDescription>
            {t('adminTemplates.catalogueDesc', { count: globals.length, defaults: defaultsByType.size })}
          </CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          {globals.length === 0 ? (
            <EmptyState
              title={t('adminTemplates.emptyTitle')}
              description={t('adminTemplates.emptyDesc')}
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminTemplates.colName')}</TableHead>
                  <TableHead>{t('adminTemplates.colDocType')}</TableHead>
                  <TableHead>{t('adminTemplates.colFormat')}</TableHead>
                  <TableHead>{t('adminTemplates.colTriggers')}</TableHead>
                  <TableHead>{t('adminTemplates.colDefault')}</TableHead>
                  <TableHead>{t('adminTemplates.colVersion')}</TableHead>
                  <TableHead className="text-right">{t('adminTemplates.colActions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {globals.map((tpl) => {
                  const isDefault = defaultsByType.get(tpl.docType)?.id === tpl.id;
                  return (
                    <TableRow key={tpl.id}>
                      <TableCell>
                        <div className="font-medium">{tpl.name}</div>
                        <code className="text-xs text-muted-foreground">{tpl.slug}</code>
                      </TableCell>
                      <TableCell>
                        <Badge variant="muted">{tpl.docType}</Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">{tpl.outputFormat ?? 'MARKDOWN'}</Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          {tpl.autoGenerateOnCommit && (
                            <Badge variant="muted" className="text-[10px]">commit</Badge>
                          )}
                          {tpl.autoGenerateOnPr && (
                            <Badge variant="muted" className="text-[10px]">PR</Badge>
                          )}
                          {tpl.autoGenerateOnMerge && (
                            <Badge variant="muted" className="text-[10px]">merge</Badge>
                          )}
                          {!tpl.autoGenerateOnCommit && !tpl.autoGenerateOnPr && !tpl.autoGenerateOnMerge && (
                            <span className="text-xs text-muted-foreground">—</span>
                          )}
                        </div>
                      </TableCell>
                      <TableCell>
                        {isDefault ? (
                          <Badge variant="success" className="gap-1">
                            <CheckCircle2 className="h-3 w-3" /> {t('adminTemplates.defaultForType')}
                          </Badge>
                        ) : (
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 text-xs"
                            onClick={() => void onSetDefault(tpl)}
                          >
                            <Star className="mr-1 h-3 w-3" /> {t('adminTemplates.makeDefault')}
                          </Button>
                        )}
                      </TableCell>
                      <TableCell>
                        <Badge variant="muted">v{tpl.version}</Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 text-xs"
                            onClick={() => openEdit(tpl)}
                          >
                            <Pencil className="mr-1 h-3 w-3" /> {t('common.edit')}
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 text-xs text-destructive"
                            onClick={() => void onDelete(tpl)}
                          >
                            <Trash2 className="mr-1 h-3 w-3" /> {t('common.delete')}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={formOpen} onOpenChange={(o) => { if (!o) setFormOpen(false); }}>
        <DialogContent className="max-w-3xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editing ? t('adminTemplates.editTitle') : t('adminTemplates.createTitle')}
            </DialogTitle>
            <DialogDescription>
              {editing
                ? t('adminTemplates.editDesc', { name: editing.name, version: editing.version })
                : t('adminTemplates.createDesc')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="adm-tpl-name">Name *</Label>
                <Input
                  id="adm-tpl-name"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  maxLength={120}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="adm-tpl-slug">Slug {!editing && '*'}</Label>
                <Input
                  id="adm-tpl-slug"
                  value={form.slug}
                  onChange={(e) => setForm({ ...form, slug: e.target.value })}
                  disabled={Boolean(editing)}
                  placeholder={editing ? '(immutable)' : 'my-template'}
                />
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="adm-tpl-type">Doc type</Label>
                <Select
                  value={form.docType}
                  onValueChange={(v) => setForm({ ...form, docType: v })}
                  disabled={Boolean(editing)}
                >
                  <SelectTrigger id="adm-tpl-type"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {DOC_TYPES.map((d) => (
                      <SelectItem key={d.value} value={d.value}>{d.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="adm-tpl-output">Output format</Label>
                <Select
                  value={form.outputFormat}
                  onValueChange={(v) => setForm({ ...form, outputFormat: v as OutputFormat })}
                >
                  <SelectTrigger id="adm-tpl-output"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {OUTPUT_FORMATS.map((f) => (
                      <SelectItem key={f} value={f}>{f}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="adm-tpl-desc">Description</Label>
              <Input
                id="adm-tpl-desc"
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
                maxLength={500}
              />
            </div>

            <div className="space-y-2 rounded-md border bg-muted/30 p-3">
              <div className="text-sm font-medium">Auto-generation triggers</div>
              <div className="grid grid-cols-3 gap-3">
                <label className="flex items-center gap-2 text-sm cursor-pointer">
                  <Switch
                    checked={form.autoOnCommit}
                    onCheckedChange={(v) => setForm({ ...form, autoOnCommit: v })}
                  />
                  On commit
                </label>
                <label className="flex items-center gap-2 text-sm cursor-pointer">
                  <Switch
                    checked={form.autoOnPr}
                    onCheckedChange={(v) => setForm({ ...form, autoOnPr: v })}
                  />
                  On pull request
                </label>
                <label className="flex items-center gap-2 text-sm cursor-pointer">
                  <Switch
                    checked={form.autoOnMerge}
                    onCheckedChange={(v) => setForm({ ...form, autoOnMerge: v })}
                  />
                  On merge
                </label>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="adm-tpl-body">Body (JSON)</Label>
              <Textarea
                id="adm-tpl-body"
                value={form.bodyJson}
                onChange={(e) => setForm({ ...form, bodyJson: e.target.value })}
                rows={14}
                className="font-mono text-xs"
              />
            </div>

            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <Switch
                checked={form.isDefault}
                onCheckedChange={(v) => setForm({ ...form, isDefault: v })}
              />
              {t('adminTemplates.defaultHint')}
            </label>

            {formError && (
              <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                {formError}
              </div>
            )}
          </div>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">{t('common.cancel')}</Button>
            </DialogClose>
            <Button onClick={() => void onSubmit()} disabled={saving}>
              {saving ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <Save className="mr-1 h-4 w-4" />
              )}
              {editing ? t('common.update') : t('common.create')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
