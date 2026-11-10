/**
 * Health & compliance report export client.
 *
 * <p>The health dashboard shows the numbers on screen; this client
 * downloads the same numbers as a file — a CSV for a spreadsheet, a
 * JSON document for a tool that has to reconcile the report against
 * another system.
 *
 * <p>Both exports require the `HEALTH_DASHBOARD_VIEW` permission and
 * workspace membership, exactly like the dashboard itself.
 */
import apiClient from './api';

/** The formats the export endpoints understand. */
export type HealthExportFormat = 'csv' | 'json';

/**
 * Download a file through the authenticated API client and hand it to
 * the browser as a download.
 *
 * <p>The response is fetched as a blob rather than opened in a new
 * window: the endpoint is behind the JWT, and a `window.open` would
 * not carry the `Authorization` header — the request would come back
 * as a 401 page instead of the report.
 */
async function downloadBlob(
  url: string,
  filename: string,
  format: HealthExportFormat,
): Promise<void> {
  const response = await apiClient.get(url, {
    params: { format },
    responseType: 'blob',
  });

  const blob = response.data as Blob;
  const objectUrl = URL.createObjectURL(blob);
  try {
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    // The object URL keeps the blob alive in memory until it is
    // revoked — a leak if it is not.
    URL.revokeObjectURL(objectUrl);
  }
}

/**
 * Download the health report of a workspace: one row per document.
 *
 * @param workspaceId the workspace the report belongs to
 * @param format      `csv` (the default) or `json`
 */
export async function exportHealthReport(
  workspaceId: string,
  format: HealthExportFormat = 'csv',
): Promise<void> {
  await downloadBlob(
    `/workspaces/${workspaceId}/health/export`,
    `health-report.${format}`,
    format,
  );
}

/**
 * Download the template-compliance report of a workspace: one row per
 * live version.
 *
 * @param workspaceId the workspace the report belongs to
 * @param format      `csv` (the default) or `json`
 */
export async function exportComplianceReport(
  workspaceId: string,
  format: HealthExportFormat = 'csv',
): Promise<void> {
  await downloadBlob(
    `/workspaces/${workspaceId}/compliance/export`,
    `compliance-report.${format}`,
    format,
  );
}

export const healthExportApi = {
  exportHealthReport,
  exportComplianceReport,
};
