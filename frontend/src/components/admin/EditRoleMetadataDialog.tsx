import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Pencil, Save } from 'lucide-react';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../ui/dialog';
import { ErrorState } from '../ui/states';
import { describeError } from '../../services/auth';
import { adminApi } from '../../services/adminApi';
import { Role } from '../../types/admin';

/**
 * Edit the presentation of a platform role.
 *
 * <p>Only the presentation is editable: the display name, the
 * description and where the role sits in the role picker. The role's
 * `code` is the identifier that JWTs, audit payloads and permission
 * grants refer to, so it is not editable from the API — changing it
 * would invalidate every assignment pointing at the role.
 */
export default function EditRoleMetadataDialog({
  role,
  onSaved,
}: {
  role: Role;
  onSaved: (updated: Role) => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [name, setName] = useState(role.name);
  const [description, setDescription] = useState(role.description ?? '');
  const [displayOrder, setDisplayOrder] = useState<string>(String(role.displayOrder));

  function reset() {
    setName(role.name);
    setDescription(role.description ?? '');
    setDisplayOrder(String(role.displayOrder));
    setError(null);
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const order = Number(displayOrder);
    if (!Number.isInteger(order) || order < 0) {
      setError(t('adminRoles.invalidDisplayOrder'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const updated = await adminApi.updateRoleMetadata(role.id, {
        name: name.trim(),
        description: description.trim() || null,
        displayOrder: order,
      });
      onSaved(updated);
      setOpen(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        setOpen(o);
        if (!o) reset();
      }}
    >
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" className="h-7 text-xs">
          <Pencil className="mr-1 h-3 w-3" /> {t('adminRoles.editRole')}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('adminRoles.editRole')}</DialogTitle>
          <DialogDescription>
            {t('adminRoles.editRoleDesc', { code: role.code })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor={`role-name-${role.id}`}>{t('adminRoles.roleName')}</Label>
            <Input
              id={`role-name-${role.id}`}
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={120}
              required
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`role-desc-${role.id}`}>{t('adminRoles.roleDescription')}</Label>
            <Textarea
              id={`role-desc-${role.id}`}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={500}
              rows={3}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`role-order-${role.id}`}>{t('adminRoles.roleDisplayOrder')}</Label>
            <Input
              id={`role-order-${role.id}`}
              type="number"
              min={0}
              max={9999}
              value={displayOrder}
              onChange={(e) => setDisplayOrder(e.target.value)}
            />
          </div>
          {error && <ErrorState message={error} />}
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">{t('common.cancel')}</Button>
            </DialogClose>
            <Button type="submit" disabled={busy}>
              {busy ? (
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
              ) : (
                <Save className="mr-1 h-4 w-4" />
              )}
              {t('common.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
