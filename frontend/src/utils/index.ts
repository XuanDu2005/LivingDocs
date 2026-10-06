// Small utility helpers. Keep this list lean — only cross-page utilities
// belong here. Page-local helpers should live with their consumer.

export function classNames(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(' ');
}