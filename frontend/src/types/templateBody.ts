// Canonical template body shape shared by the visual builder and the
// raw JSON editor. Mirrors {@code com.livingdocs.modules.template.service.TemplateSchema}.

export interface Placeholder {
  key: string;
  prompt: string;
  required?: boolean;
  maxWords?: number;
  binding?: string;
}

export interface Section {
  id: string;
  heading: string;
  level: 1 | 2 | 3 | 4 | 5 | 6;
  placeholders: Placeholder[];
}

export interface TemplateVariable {
  key: string;
  default?: string;
  description?: string;
}

export interface TemplateBody {
  version: 1;
  titleHint?: string;
  sections: Section[];
  variables?: TemplateVariable[];
}

export interface DocTypeInfo {
  value: string;
  label: string;
  description: string;
}

export interface PlaceholderFieldDoc {
  field: string;
  type: string;
  description: string;
}

export interface TemplateSchemaResponse {
  canonicalVersion: number;
  sample: TemplateBody;
  placeholderFields: PlaceholderFieldDoc[];
  docTypes: DocTypeInfo[];
}

export const EMPTY_BODY: TemplateBody = {
  version: 1,
  titleHint: '',
  sections: [],
  variables: [],
};

export function bodyToJson(body: TemplateBody): string {
  return JSON.stringify(body, null, 2);
}

export function jsonToBody(s: string): TemplateBody {
  const parsed = JSON.parse(s);
  // Defensive normalisation: always return a TemplateBody.
  if (!parsed || typeof parsed !== 'object') return { ...EMPTY_BODY };
  return {
    version: 1,
    titleHint: typeof parsed.titleHint === 'string' ? parsed.titleHint : '',
    sections: Array.isArray(parsed.sections)
      ? parsed.sections.map((sec: any) => ({
          id: String(sec.id ?? ''),
          heading: String(sec.heading ?? ''),
          level: Number(sec.level ?? 2) as Section['level'],
          placeholders: Array.isArray(sec.placeholders)
            ? sec.placeholders.map((p: any) => ({
                key: String(p.key ?? ''),
                prompt: String(p.prompt ?? ''),
                required: Boolean(p.required),
                maxWords: typeof p.maxWords === 'number' ? p.maxWords : undefined,
                binding: typeof p.binding === 'string' ? p.binding : undefined,
              }))
            : [],
        }))
      : [],
    variables: Array.isArray(parsed.variables)
      ? parsed.variables.map((v: any) => ({
          key: String(v.key ?? ''),
          default: v.default,
          description: v.description,
        }))
      : [],
  };
}

export function uniqueSectionId(existing: Section[]): string {
  let i = 1;
  while (existing.some((s) => s.id === `section-${i}`)) i++;
  return `section-${i}`;
}

export function uniquePlaceholderKey(section: Section): string {
  let i = 1;
  while (section.placeholders.some((p) => p.key === `placeholder-${i}`)) i++;
  return `placeholder-${i}`;
}
