import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { Badge } from './ui/badge';

type DiffLineKind = 'context' | 'add' | 'remove' | 'hunk' | 'file' | 'meta';

interface ParsedLine {
  kind: DiffLineKind;
  text: string;
  /** File this line belongs to, used to group hunks. */
  file?: string;
}

interface DiffFile {
  path: string;
  lines: ParsedLine[];
  added: number;
  removed: number;
}

/**
 * Parse a unified diff into per-file hunks.
 *
 * <p>The diff is produced by the backend (Java) and by the AI service
 * (Python `difflib`), so we only rely on the standard unified-diff
 * markers: `---`/`+++` file headers and `@@` hunk headers.
 */
export function parseUnifiedDiff(diff: string): DiffFile[] {
  if (!diff) return [];
  const raw = diff.split('\n');
  const files: DiffFile[] = [];

  // The index of the file the current line belongs to, or -1 when we have
  // not seen a file header yet. An index avoids the control-flow problems
  // a nullable "current file" variable would cause here.
  let currentIndex = -1;

  const startFile = (path: string): number => {
    files.push({ path, lines: [], added: 0, removed: 0 });
    currentIndex = files.length - 1;
    return currentIndex;
  };

  /** The file the current line belongs to, starting a synthetic one if needed. */
  const currentFile = (): DiffFile => {
    if (currentIndex < 0) startFile('diff');
    return files[currentIndex];
  };

  for (let i = 0; i < raw.length; i++) {
    const line = raw[i];

    // `--- a/path` followed by `+++ b/path` starts a new file section.
    if (line.startsWith('--- ') && raw[i + 1]?.startsWith('+++ ')) {
      startFile(raw[i + 1].slice(4).trim());
      i++;
      continue;
    }

    // `/dev/null` markers used by the Python differ for added/removed files.
    if (line.startsWith('--- ') && raw[i + 1]?.startsWith('+++ /dev/null')) {
      startFile(line.slice(4).trim());
      i++;
      continue;
    }

    if (line.startsWith('@@')) {
      currentFile().lines.push({ kind: 'hunk', text: line });
      continue;
    }

    if (line.startsWith('diff ') || line.startsWith('index ')) {
      continue;
    }

    if (line.startsWith('+')) {
      const file = currentFile();
      file.added++;
      file.lines.push({ kind: 'add', text: line, file: file.path });
      continue;
    }

    if (line.startsWith('-')) {
      const file = currentFile();
      file.removed++;
      file.lines.push({ kind: 'remove', text: line, file: file.path });
      continue;
    }

    if (line.startsWith('\\')) {
      continue; // "\ No newline at end of file"
    }

    if (currentIndex >= 0) {
      const file = files[currentIndex];
      file.lines.push({ kind: 'context', text: line, file: file.path });
    }
  }

  return files;
}

function LineClass(kind: DiffLineKind): string {
  switch (kind) {
    case 'add':
      return 'bg-green-500/10 text-green-700 dark:text-green-400';
    case 'remove':
      return 'bg-red-500/10 text-red-700 dark:text-red-400';
    case 'hunk':
      return 'bg-blue-500/10 text-blue-700 dark:text-blue-400 font-semibold';
    default:
      return 'text-muted-foreground';
  }
}

/**
 * Renders a unified diff with per-file collapsible sections and
 * add/remove counters.
 */
export default function UnifiedDiffViewer({
  diff,
  emptyMessage = 'No differences.',
}: {
  diff: string;
  emptyMessage?: string;
}) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const files = useMemo(() => parseUnifiedDiff(diff), [diff]);

  if (!diff || !diff.trim()) {
    return (
      <p className="rounded border bg-muted/30 p-4 text-sm text-muted-foreground">
        {emptyMessage}
      </p>
    );
  }

  // Fall back to the raw text when the diff has no recognizable headers.
  if (files.length === 0) {
    return (
      <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded bg-muted/50 p-3 font-mono text-xs">
        {diff}
      </pre>
    );
  }

  const totalAdded = files.reduce((s, f) => s + f.added, 0);
  const totalRemoved = files.reduce((s, f) => s + f.removed, 0);

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-xs">
        <Badge variant="success">+{totalAdded}</Badge>
        <Badge variant="destructive">-{totalRemoved}</Badge>
        <span className="text-muted-foreground">
          {files.length} {files.length === 1 ? 'file' : 'files'} changed
        </span>
      </div>

      {files.map((file) => {
        const isCollapsed = collapsed[file.path] ?? false;
        return (
          <div key={file.path} className="overflow-hidden rounded border">
            <button
              type="button"
              onClick={() =>
                setCollapsed((c) => ({ ...c, [file.path]: !isCollapsed }))
              }
              className="flex w-full items-center gap-2 bg-muted/40 px-3 py-2 text-left text-xs font-mono hover:bg-muted/60"
            >
              {isCollapsed ? (
                <ChevronRight className="h-3 w-3 shrink-0" />
              ) : (
                <ChevronDown className="h-3 w-3 shrink-0" />
              )}
              <span className="flex-1 truncate">{file.path}</span>
              {file.added > 0 && <span className="text-green-600">+{file.added}</span>}
              {file.removed > 0 && <span className="text-red-600">-{file.removed}</span>}
            </button>
            {!isCollapsed && (
              <pre className="max-h-96 overflow-auto bg-muted/20 p-2 font-mono text-xs">
                {file.lines.map((line, i) => (
                  <div key={i} className={LineClass(line.kind)}>
                    {line.text}
                  </div>
                ))}
              </pre>
            )}
          </div>
        );
      })}
    </div>
  );
}
