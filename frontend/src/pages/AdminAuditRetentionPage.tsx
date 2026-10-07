import { useEffect, useState } from 'react';
import { Trash2, ShieldAlert } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Label } from '../components/ui/label';
import { Input } from '../components/ui/input';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { AuditRetentionPolicy, PruneStrategy } from '../types/admin';
import { format } from 'date-fns';

export default function AdminAuditRetentionPage() {
  const [policies, setPolicies] = useState<AuditRetentionPolicy[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [editExisting, setEditExisting] = useState<AuditRetentionPolicy | null>(null);
  // Track whether the create/edit dialog is open. We use a separate flag
  // rather than `editExisting` because the latter is also `null` when no
  // policy has been loaded yet — using it as the "open" signal would mean
  // the dialog auto-opens on first render.
  const [dialogOpen, setDialogOpen] = useState<boolean>(false);
  const [entityType, setEntityType] = useState<string>('');
  const [retentionDays, setRetentionDays] = useState<number>(90);
  const [pruneStrategy, setPruneStrategy] = useState<PruneStrategy>('HARD_DELETE');
  const [enabled, setEnabled] = useState<boolean>(true);
  const [description, setDescription] = useState<string>('');

  useEffect(() => {
    void load();
  }, []);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const data = await adminApi.listRetentionPolicies();
      setPolicies(data);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  function openCreate() {
    setEditExisting(null);
    setEntityType('');
    setRetentionDays(90);
    setPruneStrategy('HARD_DELETE');
    setEnabled(true);
    setDescription('');
    setDialogOpen(true);
  }

  function openEdit(p: AuditRetentionPolicy) {
    setEditExisting(p);
    setEntityType(p.entityType);
    setRetentionDays(p.retentionDays);
    setPruneStrategy(p.pruneStrategy);
    setEnabled(p.enabled);
    setDescription(p.description ?? '');
    setDialogOpen(true);
  }

  function closeDialog() {
    setDialogOpen(false);
    // Reset form fields so re-opening in "create" mode starts fresh.
    setEditExisting(null);
    setEntityType('');
    setRetentionDays(90);
    setPruneStrategy('HARD_DELETE');
    setEnabled(true);
    setDescription('');
  }

  async function save() {
    setBusy(entityType || 'new');
    try {
      await adminApi.upsertRetentionPolicy({
        entityType,
        existingEntityType: editExisting?.entityType,
        retentionDays,
        pruneStrategy,
        enabled,
        description: description || null,
      });
      closeDialog();
      await load();
    } catch (err) {
      setError(describeError(err));
      // Keep the dialog open on error so the user can fix the form.
    } finally {
      setBusy(null);
    }
  }

  async function remove(p: AuditRetentionPolicy) {
    if (!confirm(`Delete retention policy for "${p.entityType}"?`)) return;
    setBusy(p.entityType);
    try {
      await adminApi.deleteRetentionPolicy(p.entityType);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  if (loading) return <LoadingState message="Loading retention policies…" />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Admin · Audit Retention</h1>
        <p className="text-sm text-muted-foreground">
          Define how long rows of each entity type are kept before the configured
          prune strategy is applied. Compliance minimums are seeded by default and
          can be adjusted per entity.
        </p>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base flex items-center gap-2">
            <ShieldAlert className="h-4 w-4" /> Retention policies
            <Badge variant="muted">{policies.length}</Badge>
          </CardTitle>
          <Dialog
            open={dialogOpen}
            onOpenChange={(o) => {
              if (o) {
                // Only initialise the form when the user *opens* the dialog
                // via the trigger button (not from a programmatic re-open).
                if (!dialogOpen) openCreate();
              } else {
                closeDialog();
              }
            }}
          >
            <DialogTrigger asChild>
              <Button size="sm">New policy</Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>
                  {editExisting ? `Edit "${editExisting.entityType}"` : 'New retention policy'}
                </DialogTitle>
                <DialogDescription>
                  Policies are uniquely keyed by entity type. Updating the entity
                  type field renames the policy.
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <Label htmlFor="entityType">Entity type</Label>
                  <Input
                    id="entityType"
                    value={entityType}
                    onChange={(e) => setEntityType(e.target.value)}
                    placeholder="e.g. webhook_event"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="retentionDays">Retention (days)</Label>
                  <Input
                    id="retentionDays"
                    type="number"
                    min={0}
                    value={retentionDays}
                    onChange={(e) => setRetentionDays(Number(e.target.value))}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="pruneStrategy">Prune strategy</Label>
                  <Select value={pruneStrategy} onValueChange={(v) => setPruneStrategy(v as PruneStrategy)}>
                    <SelectTrigger id="pruneStrategy">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="HARD_DELETE">HARD_DELETE — physically delete</SelectItem>
                      <SelectItem value="ARCHIVE">ARCHIVE — move to cold storage</SelectItem>
                      <SelectItem value="ANONYMIZE">ANONYMIZE — strip PII, keep row</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="description">Description (optional)</Label>
                  <Input
                    id="description"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="Why does this entity need this retention window?"
                  />
                </div>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-border"
                    checked={enabled}
                    onChange={(e) => setEnabled(e.target.checked)}
                  />
                  Enabled
                </label>
              </div>
              <DialogFooter>
                <DialogClose asChild>
                  <Button variant="outline" type="button" onClick={closeDialog}>Cancel</Button>
                </DialogClose>
                <Button
                  type="button"
                  onClick={() => void save()}
                  disabled={
                    busy === entityType ||
                    !entityType.trim() ||
                    retentionDays < 0
                  }
                >
                  {editExisting ? 'Save changes' : 'Create policy'}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Entity type</TableHead>
                <TableHead>Retention</TableHead>
                <TableHead>Strategy</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Last pruned</TableHead>
                <TableHead>Updated</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {policies.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-sm text-muted-foreground py-6">
                    No retention policies configured yet.
                  </TableCell>
                </TableRow>
              )}
              {policies.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>
                    <div className="font-medium">{p.entityType}</div>
                    {p.description && (
                      <div className="text-xs text-muted-foreground">{p.description}</div>
                    )}
                  </TableCell>
                  <TableCell className="whitespace-nowrap">{p.retentionDays} days</TableCell>
                  <TableCell>
                    <Badge variant="muted" className="text-[10px] font-mono">
                      {p.pruneStrategy}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={p.enabled ? 'success' : 'muted'} className="text-[10px]">
                      {p.enabled ? 'enabled' : 'disabled'}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                    {p.lastPrunedAt ? format(new Date(p.lastPrunedAt), 'MMM d, HH:mm') : '—'}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                    {format(new Date(p.updatedAt), 'MMM d, yyyy')}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1">
                      <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => openEdit(p)}>
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-7 text-xs text-destructive"
                        disabled={busy === p.entityType}
                        onClick={() => void remove(p)}
                      >
                        <Trash2 className="h-3 w-3" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}