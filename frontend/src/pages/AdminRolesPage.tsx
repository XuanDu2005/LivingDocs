import { useEffect, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { Role } from '../types/admin';
import { format } from 'date-fns';

export default function AdminRolesPage() {
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void load();
  }, []);

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

  if (loading) return <LoadingState message="Loading roles…" />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Admin · Roles</h1>
        <p className="text-sm text-muted-foreground">
          Catalogue of platform roles. Role codes are immutable identifiers used
          by the API and audit log; names and descriptions are editable to keep
          the UI accurate as the organization evolves.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <ShieldCheck className="h-4 w-4" /> Role catalogue
            <Badge variant="muted">{roles.length}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Order</TableHead>
                <TableHead>Updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {roles.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>
                    <Badge variant="muted" className="font-mono text-[11px]">
                      {r.code}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-medium">{r.name}</TableCell>
                  <TableCell className="text-xs text-muted-foreground max-w-md">
                    {r.description ?? '—'}
                  </TableCell>
                  <TableCell>
                    <Badge variant={r.system ? 'success' : 'muted'} className="text-[10px]">
                      {r.system ? 'system' : 'custom'}
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
          <CardTitle className="text-base">How role enforcement works</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-muted-foreground">
          <p>
            Each authenticated request resolves the user's active role codes from
            the <span className="font-mono">user_roles</span> table and grants
            them as Spring Security authorities (prefixed with{' '}
            <span className="font-mono">ROLE_</span>).
          </p>
          <p>
            Platform endpoints are annotated with{' '}
            <span className="font-mono">@RequirePlatformRole</span>; the
            interceptor rejects the request unless the principal owns at least
            one of the listed role codes.
          </p>
          <p>
            The five seeded roles — DEVELOPER, STAFF, TECHNICAL_LEAD, MANAGER,
            ADMIN — are <span className="font-medium">immutable identifiers</span>.
            Adding new platform roles requires a new Flyway migration so JWTs
            and audit payloads remain compatible.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}