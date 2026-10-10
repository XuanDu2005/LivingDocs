import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ShieldCheck, Table as TableIcon, LayoutGrid } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '../components/ui/tabs';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { roleDescription, roleName } from '../services/adminLabels';
import { Role } from '../types/admin';
import { format } from 'date-fns';
import RolePermissionMatrix from '../components/RolePermissionMatrix';

export default function AdminRolesPage() {
  const { t } = useTranslation();
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { void load(); }, []);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const data = await adminApi.listRoles();
      setRoles(data);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  if (loading) return <LoadingState message={t('adminRoles.loading')} />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('adminRoles.title')}</h1>
        <p className="text-sm text-muted-foreground">
          {t('adminRoles.subtitle')}
        </p>
      </div>

      <Tabs defaultValue="catalogue" className="space-y-4">
        <TabsList>
          <TabsTrigger value="catalogue">
            <TableIcon className="mr-1 h-4 w-4" />
            {t('adminRoles.tabCatalogue', 'Catalogue')}
          </TabsTrigger>
          <TabsTrigger value="matrix">
            <LayoutGrid className="mr-1 h-4 w-4" />
            {t('adminRoles.tabMatrix', 'Permissions matrix')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="catalogue" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base flex items-center gap-2">
                <ShieldCheck className="h-4 w-4" /> {t('adminRoles.catalogue')}
                <Badge variant="muted">{roles.length}</Badge>
              </CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('adminRoles.colCode')}</TableHead>
                    <TableHead>{t('adminRoles.colName')}</TableHead>
                    <TableHead>{t('adminRoles.colDescription')}</TableHead>
                    <TableHead>{t('adminRoles.colType')}</TableHead>
                    <TableHead>{t('adminRoles.colOrder')}</TableHead>
                    <TableHead>{t('adminRoles.colUpdated')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {roles.map((r) => (
                    <TableRow key={r.id}>
                      <TableCell>
                        <Badge variant="muted" className="font-mono text-[11px]">{r.code}</Badge>
                      </TableCell>
                      <TableCell className="font-medium">{roleName(r.code, r.name, t)}</TableCell>
                      <TableCell className="text-xs text-muted-foreground max-w-md">{roleDescription(r.code, r.description, t)}</TableCell>
                      <TableCell>
                        <Badge variant={r.system ? 'success' : 'muted'} className="text-[10px]">
                          {r.system ? t('adminRoles.system') : t('adminRoles.custom')}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs">{r.displayOrder}</TableCell>
                      <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                        {format(new Date(r.updatedAt), 'MMM d, yyyy')}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('adminRoles.howItWorks')}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm text-muted-foreground">
              <p>
                {t('adminRoles.howItWorksP1')}{' '}
                <span className="font-mono">user_roles</span> {t('adminRoles.howItWorksP1Mid')}{' '}
                <span className="font-mono">ROLE_</span>.
              </p>
              <p>
                {t('adminRoles.howItWorksP2')}{' '}
                <span className="font-mono">@RequirePlatformRole</span>.
              </p>
              <p>
                {t('adminRoles.howItWorksP3')}
              </p>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="matrix">
          <RolePermissionMatrix roles={roles} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
