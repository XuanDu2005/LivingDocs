/**
 * The composite "ingest everything" flow of a connected repository.
 *
 * <p>The repository detail page exposes three separate ingestion steps —
 * pull requests, code entities and modules — and modules are *derived*
 * from the indexed code entities on the backend. A user who only pulled
 * the pull requests therefore ended up with an empty entity list and an
 * empty module list, which looked like the ingest had failed. This
 * module runs the three steps in the right order so the caller gets a
 * consistent snapshot in a single call.
 */
import { githubApi, PullRequest, RepositoryModule } from './github';
import { codeApi, CodeEntity } from './knowledge';

export interface IngestAllResult {
  /** The pull requests fetched from GitHub. */
  pullRequests: PullRequest[];
  /** The number of code entities extracted by this run. */
  extractedEntityCount: number;
  /** The code entities currently indexed for the repository. */
  entities: CodeEntity[];
  /** The module directories detected from the indexed code entities. */
  detectedModules: RepositoryModule[];
  /**
   * The modules that were actually written to the database. Persisting a
   * module requires the {@code MODULE_AUTO_UPDATE_MANAGE} permission, so
   * this can be empty for a reader without that permission.
   */
  savedModules: RepositoryModule[];
}

/**
 * Ingest every kind of data of a repository: pull requests, then the
 * code entities, then the module directories derived from them.
 *
 * <p>The steps are sequential on purpose: the module detection reads the
 * code entities, so it has to run after the entity extraction.
 */
export async function ingestRepositoryEverything(
  workspaceId: string,
  repositoryId: string,
): Promise<IngestAllResult> {
  const prs = await githubApi.ingestPullRequests(workspaceId, repositoryId, 'all');
  const code = await githubApi.ingestCode(workspaceId, repositoryId);
  const entities = await codeApi.listEntities(workspaceId, repositoryId);
  const detectedModules = await githubApi.detectModules(workspaceId, repositoryId);

  // Persisting a module needs the MODULE_AUTO_UPDATE_MANAGE permission.
  // A reader without it still gets the entities indexed — the module
  // rows are simply left to whoever does hold the permission. Failing
  // the whole ingest because of that would be worse than skipping it.
  const saved = await Promise.all(
    detectedModules.map((m) =>
      githubApi
        .upsertModule(
          workspaceId,
          repositoryId,
          m.modulePath,
          m.autoUpdateEnabled,
          m.displayName ?? undefined,
        )
        .catch(() => null),
    ),
  );
  const savedModules = saved.filter(Boolean) as RepositoryModule[];

  return {
    pullRequests: prs,
    extractedEntityCount: code.ingested,
    entities,
    detectedModules,
    savedModules,
  };
}
