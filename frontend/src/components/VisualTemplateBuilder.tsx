import { useCallback } from 'react';
import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react';
import { Button } from './ui/button';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Placeholder, Section, TemplateBody, uniquePlaceholderKey, uniqueSectionId } from '../types/templateBody';

export interface VisualTemplateBuilderProps {
  body: TemplateBody;
  onChange: (body: TemplateBody) => void;
}

/**
 * Visual builder for a {@link TemplateBody}. Edits an in-memory copy
 * of the body via callback. The JSON editor / preview on the same
 * page is kept in sync so the user can switch modes at will.
 */
export function VisualTemplateBuilder({ body, onChange }: VisualTemplateBuilderProps) {
  const update = useCallback(
    (mutator: (b: TemplateBody) => TemplateBody) => onChange(mutator(body)),
    [body, onChange],
  );

  function moveSection(idx: number, dir: -1 | 1) {
    update((b) => {
      const sections = [...b.sections];
      const target = idx + dir;
      if (target < 0 || target >= sections.length) return b;
      [sections[idx], sections[target]] = [sections[target], sections[idx]];
      return { ...b, sections };
    });
  }

  function removeSection(idx: number) {
    update((b) => ({ ...b, sections: b.sections.filter((_, i) => i !== idx) }));
  }

  function patchSection(idx: number, patch: Partial<Section>) {
    update((b) => ({
      ...b,
      sections: b.sections.map((s, i) => (i === idx ? { ...s, ...patch } : s)),
    }));
  }

  function addSection() {
    update((b) => {
      const id = uniqueSectionId(b.sections);
      return {
        ...b,
        sections: [
          ...b.sections,
          { id, heading: 'New section', level: 2, placeholders: [] },
        ],
      };
    });
  }

  function addPlaceholder(sectionIdx: number) {
    update((b) => {
      const sections = b.sections.map((s, i) => {
        if (i !== sectionIdx) return s;
        const key = uniquePlaceholderKey(s);
        return {
          ...s,
          placeholders: [
            ...s.placeholders,
            { key, prompt: '', required: false } as Placeholder,
          ],
        };
      });
      return { ...b, sections };
    });
  }

  function removePlaceholder(sectionIdx: number, phIdx: number) {
    update((b) => ({
      ...b,
      sections: b.sections.map((s, i) =>
        i === sectionIdx
          ? { ...s, placeholders: s.placeholders.filter((_, j) => j !== phIdx) }
          : s
      ),
    }));
  }

  function patchPlaceholder(sectionIdx: number, phIdx: number, patch: Partial<Placeholder>) {
    update((b) => ({
      ...b,
      sections: b.sections.map((s, i) =>
        i === sectionIdx
          ? {
              ...s,
              placeholders: s.placeholders.map((p, j) => (j === phIdx ? { ...p, ...patch } : p)),
            }
          : s
      ),
    }));
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="builder-titleHint">Title hint (supports {'{{variable}}'})</Label>
        <Input
          id="builder-titleHint"
          value={body.titleHint ?? ''}
          onChange={(e) => update((b) => ({ ...b, titleHint: e.target.value }))}
          placeholder="e.g. Module Guide — {{moduleName}}"
        />
      </div>

      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Sections ({body.sections.length})</h3>
        <Button size="sm" variant="outline" onClick={addSection}>
          <Plus className="mr-1 h-3 w-3" /> Add section
        </Button>
      </div>

      {body.sections.length === 0 ? (
        <Card>
          <CardContent className="pt-6 text-sm text-muted-foreground text-center">
            No sections yet. Add one to get started.
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-3">
          {body.sections.map((section, sIdx) => (
            <Card key={`${section.id}-${sIdx}`}>
              <CardHeader className="pb-2">
                <div className="flex items-center justify-between gap-2">
                  <CardTitle className="text-sm font-medium">
                    Section #{sIdx + 1}
                  </CardTitle>
                  <div className="flex gap-1">
                    <Button size="icon" variant="ghost" className="h-7 w-7"
                      onClick={() => moveSection(sIdx, -1)} disabled={sIdx === 0}>
                      <ArrowUp className="h-3 w-3" />
                    </Button>
                    <Button size="icon" variant="ghost" className="h-7 w-7"
                      onClick={() => moveSection(sIdx, 1)} disabled={sIdx === body.sections.length - 1}>
                      <ArrowDown className="h-3 w-3" />
                    </Button>
                    <Button size="icon" variant="ghost" className="h-7 w-7 text-destructive"
                      onClick={() => removeSection(sIdx)}>
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </div>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_2fr_80px]">
                  <div className="space-y-1.5">
                    <Label htmlFor={`sec-id-${sIdx}`}>ID</Label>
                    <Input
                      id={`sec-id-${sIdx}`}
                      value={section.id}
                      onChange={(e) => patchSection(sIdx, { id: e.target.value })}
                      className="font-mono text-xs"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor={`sec-heading-${sIdx}`}>Heading</Label>
                    <Input
                      id={`sec-heading-${sIdx}`}
                      value={section.heading}
                      onChange={(e) => patchSection(sIdx, { heading: e.target.value })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor={`sec-level-${sIdx}`}>Level</Label>
                    <select
                      id={`sec-level-${sIdx}`}
                      value={section.level}
                      onChange={(e) => patchSection(sIdx, { level: Number(e.target.value) as Section['level'] })}
                      className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                    >
                      {[1, 2, 3, 4, 5, 6].map((n) => (
                        <option key={n} value={n}>H{n}</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Placeholders ({section.placeholders.length})
                    </span>
                    <Button size="sm" variant="ghost" onClick={() => addPlaceholder(sIdx)}>
                      <Plus className="mr-1 h-3 w-3" /> Add placeholder
                    </Button>
                  </div>

                  {section.placeholders.length === 0 ? (
                    <p className="text-xs text-muted-foreground italic">
                      No placeholders. Add one to instruct the AI what to write.
                    </p>
                  ) : (
                    <div className="space-y-2">
                      {section.placeholders.map((ph, pIdx) => (
                        <div
                          key={`${section.id}-ph-${pIdx}`}
                          className="rounded-md border bg-muted/30 p-3 space-y-2"
                        >
                          <div className="grid grid-cols-1 gap-2 sm:grid-cols-[1fr_2fr_auto]">
                            <div className="space-y-1">
                              <Label htmlFor={`ph-key-${sIdx}-${pIdx}`} className="text-xs">Key</Label>
                              <Input
                                id={`ph-key-${sIdx}-${pIdx}`}
                                value={ph.key}
                                onChange={(e) => patchPlaceholder(sIdx, pIdx, { key: e.target.value })}
                                className="font-mono text-xs h-8"
                                placeholder="summary"
                              />
                            </div>
                            <div className="space-y-1">
                              <Label htmlFor={`ph-prompt-${sIdx}-${pIdx}`} className="text-xs">Prompt</Label>
                              <Input
                                id={`ph-prompt-${sIdx}-${pIdx}`}
                                value={ph.prompt}
                                onChange={(e) => patchPlaceholder(sIdx, pIdx, { prompt: e.target.value })}
                                className="h-8"
                                placeholder="What the AI should write here"
                              />
                            </div>
                            <div className="flex items-end">
                              <Button
                                size="icon" variant="ghost"
                                className="h-8 w-8 text-destructive"
                                onClick={() => removePlaceholder(sIdx, pIdx)}
                              >
                                <Trash2 className="h-3 w-3" />
                              </Button>
                            </div>
                          </div>
                          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                            <div className="space-y-1">
                              <Label htmlFor={`ph-max-${sIdx}-${pIdx}`} className="text-xs">Max words</Label>
                              <Input
                                id={`ph-max-${sIdx}-${pIdx}`}
                                type="number" min={1} max={5000}
                                value={ph.maxWords ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value ? Number(e.target.value) : undefined;
                                  patchPlaceholder(sIdx, pIdx, { maxWords: v });
                                }}
                                className="h-8 text-xs"
                                placeholder="optional"
                              />
                            </div>
                            <div className="space-y-1">
                              <Label htmlFor={`ph-binding-${sIdx}-${pIdx}`} className="text-xs">AST binding</Label>
                              <Input
                                id={`ph-binding-${sIdx}-${pIdx}`}
                                value={ph.binding ?? ''}
                                onChange={(e) => patchPlaceholder(sIdx, pIdx, { binding: e.target.value || undefined })}
                                className="h-8 text-xs font-mono"
                                placeholder="code.route"
                              />
                            </div>
                            <label className="flex items-center gap-2 pt-5 text-xs">
                              <input
                                type="checkbox"
                                checked={!!ph.required}
                                onChange={(e) => patchPlaceholder(sIdx, pIdx, { required: e.target.checked })}
                                className="h-4 w-4 rounded border-input accent-primary"
                              />
                              Required
                            </label>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
