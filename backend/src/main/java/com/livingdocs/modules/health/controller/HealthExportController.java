package com.livingdocs.modules.health.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.common.security.RequirePermission;
import com.livingdocs.modules.health.dto.ComplianceReportRow;
import com.livingdocs.modules.health.dto.HealthReportRow;
import com.livingdocs.modules.health.service.HealthReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.UUID;

/**
 * The "Health &amp; Compliance" report export.
 *
 * <p>The health dashboard shows the numbers; this endpoint turns them
 * into a file a reader can keep: a spreadsheet for the weekly review, a
 * JSON document for a tool that has to reconcile the report against
 * another system.
 *
 * <p>Two exports are offered:
 *
 * <ul>
 *   <li>{@code GET /workspaces/{id}/health/export} — one row per
 *       document, joining the documents, their versions, their drift
 *       alerts and their templates.</li>
 *   <li>{@code GET /workspaces/{id}/compliance/export} — one row per
 *       live version, with the template compliance of that version.</li>
 * </ul>
 *
 * <p>Both are gated behind the {@code HEALTH_DASHBOARD_VIEW} permission
 * — the same capability the dashboard itself requires — plus workspace
 * membership. A report is a copy of the dashboard's data outside the
 * application, so it must not be easier to obtain than the dashboard.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
@Tag(name = "Health export", description = "Health & compliance report export (CSV / JSON)")
public class HealthExportController {

    private final HealthReportService reportService;

    public HealthExportController(HealthReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * The health report: one row per document.
     *
     * @param format {@code csv} (the default) or {@code json}
     */
    @GetMapping("/health/export")
    @RequirePermission({"HEALTH_DASHBOARD_VIEW"})
    @Operation(summary = "Export the health report of a workspace (HEALTH_DASHBOARD_VIEW only)")
    public void exportHealth(@PathVariable UUID workspaceId,
                             @RequestParam(defaultValue = "csv") String format,
                             HttpServletResponse response) throws IOException {
        List<HealthReportRow> rows = reportService.healthReport(
                CurrentUser.requireId(), workspaceId);

        if (isJson(format)) {
            writeHealthJson(rows, response);
        } else {
            writeHealthCsv(rows, response);
        }
    }

    /**
     * The compliance report: one row per live version.
     *
     * @param format {@code csv} (the default) or {@code json}
     */
    @GetMapping("/compliance/export")
    @RequirePermission({"HEALTH_DASHBOARD_VIEW"})
    @Operation(summary = "Export the template-compliance report of a workspace (HEALTH_DASHBOARD_VIEW only)")
    public void exportCompliance(@PathVariable UUID workspaceId,
                                  @RequestParam(defaultValue = "csv") String format,
                                  HttpServletResponse response) throws IOException {
        List<ComplianceReportRow> rows = reportService.complianceReport(
                CurrentUser.requireId(), workspaceId);

        if (isJson(format)) {
            writeComplianceJson(rows, response);
        } else {
            writeComplianceCsv(rows, response);
        }
    }

    // =================================================================
    // The health report
    // =================================================================

    /**
     * The CSV of the health report.
     *
     * <p>The column order is the one the requirement names:
     * {@code document_id, title, slug, doc_type, status, owner_id,
     * template_name, template_compliant, missing_headings,
     * last_version, last_updated, open_drift, max_drift_severity,
     * days_since_update}.
     */
    private void writeHealthCsv(List<HealthReportRow> rows, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=health-report.csv");

        try (PrintWriter w = response.getWriter()) {
            w.println("document_id,title,slug,doc_type,status,owner_id,"
                    + "template_name,template_compliant,missing_headings,"
                    + "last_version,last_updated,open_drift,max_drift_severity,"
                    + "days_since_update");
            for (HealthReportRow row : rows) {
                w.print(row.documentId());
                w.print(",");
                w.print(escapeCsv(row.title()));
                w.print(",");
                w.print(escapeCsv(row.slug()));
                w.print(",");
                w.print(escapeCsv(row.docType()));
                w.print(",");
                w.print(escapeCsv(row.status()));
                w.print(",");
                w.print(row.ownerId() != null ? row.ownerId() : "");
                w.print(",");
                w.print(escapeCsv(row.templateName()));
                w.print(",");
                w.print(row.templateCompliant() != null ? row.templateCompliant() : "");
                w.print(",");
                w.print(escapeCsv(joinHeadings(row.missingHeadings())));
                w.print(",");
                w.print(row.lastVersion() != null ? row.lastVersion() : "");
                w.print(",");
                w.print(escapeCsv(row.lastUpdated()));
                w.print(",");
                w.print(row.openDrift() != null ? row.openDrift() : "");
                w.print(",");
                w.print(escapeCsv(row.maxDriftSeverity()));
                w.print(",");
                w.println(row.daysSinceUpdate() != null ? row.daysSinceUpdate() : "");
            }
        }
    }

    /**
     * The JSON of the health report.
     */
    private void writeHealthJson(List<HealthReportRow> rows, HttpServletResponse response)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Content-Disposition",
                "attachment; filename=health-report.json");

        try (PrintWriter w = response.getWriter()) {
            w.println("[");
            for (int i = 0; i < rows.size(); i++) {
                HealthReportRow row = rows.get(i);
                w.print("  {");
                w.print("\"documentId\":\"" + row.documentId() + "\",");
                w.print("\"title\":" + jsonString(row.title()) + ",");
                w.print("\"slug\":" + jsonString(row.slug()) + ",");
                w.print("\"docType\":" + jsonString(row.docType()) + ",");
                w.print("\"status\":" + jsonString(row.status()) + ",");
                w.print("\"ownerId\":" + jsonString(row.ownerId() == null ? null : row.ownerId().toString()) + ",");
                w.print("\"templateName\":" + jsonString(row.templateName()) + ",");
                w.print("\"templateCompliant\":" + (row.templateCompliant() == null ? "null" : row.templateCompliant()) + ",");
                w.print("\"missingHeadings\":" + jsonArray(row.missingHeadings()) + ",");
                w.print("\"lastVersion\":" + (row.lastVersion() == null ? "null" : row.lastVersion()) + ",");
                w.print("\"lastUpdated\":" + jsonString(row.lastUpdated()) + ",");
                w.print("\"openDrift\":" + (row.openDrift() == null ? "null" : row.openDrift()) + ",");
                w.print("\"maxDriftSeverity\":" + jsonString(row.maxDriftSeverity()) + ",");
                w.print("\"daysSinceUpdate\":" + (row.daysSinceUpdate() == null ? "null" : row.daysSinceUpdate()));
                w.print("}");
                if (i < rows.size() - 1) {
                    w.println(",");
                } else {
                    w.println();
                }
            }
            w.println("]");
        }
    }

    // =================================================================
    // The compliance report
    // =================================================================

    /**
     * The CSV of the compliance report.
     *
     * <p>The column order is the one the requirement names:
     * {@code document_id, version_number, template_name, compliant,
     * missing_headings, out_of_order, generated_by, created_at}.
     */
    private void writeComplianceCsv(List<ComplianceReportRow> rows, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=compliance-report.csv");

        try (PrintWriter w = response.getWriter()) {
            w.println("document_id,version_number,template_name,compliant,"
                    + "missing_headings,out_of_order,generated_by,created_at");
            for (ComplianceReportRow row : rows) {
                w.print(row.documentId());
                w.print(",");
                w.print(row.versionNumber() != null ? row.versionNumber() : "");
                w.print(",");
                w.print(escapeCsv(row.templateName()));
                w.print(",");
                w.print(row.compliant() != null ? row.compliant() : "");
                w.print(",");
                w.print(escapeCsv(joinHeadings(row.missingHeadings())));
                w.print(",");
                w.print(escapeCsv(joinHeadings(row.outOfOrder())));
                w.print(",");
                w.print(escapeCsv(row.generatedBy()));
                w.print(",");
                w.println(escapeCsv(row.createdAt()));
            }
        }
    }

    /**
     * The JSON of the compliance report.
     */
    private void writeComplianceJson(List<ComplianceReportRow> rows, HttpServletResponse response)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Content-Disposition",
                "attachment; filename=compliance-report.json");

        try (PrintWriter w = response.getWriter()) {
            w.println("[");
            for (int i = 0; i < rows.size(); i++) {
                ComplianceReportRow row = rows.get(i);
                w.print("  {");
                w.print("\"documentId\":\"" + row.documentId() + "\",");
                w.print("\"versionNumber\":" + (row.versionNumber() == null ? "null" : row.versionNumber()) + ",");
                w.print("\"templateName\":" + jsonString(row.templateName()) + ",");
                w.print("\"compliant\":" + (row.compliant() == null ? "null" : row.compliant()) + ",");
                w.print("\"missingHeadings\":" + jsonArray(row.missingHeadings()) + ",");
                w.print("\"outOfOrder\":" + jsonArray(row.outOfOrder()) + ",");
                w.print("\"generatedBy\":" + jsonString(row.generatedBy()) + ",");
                w.print("\"createdAt\":" + jsonString(row.createdAt()));
                w.print("}");
                if (i < rows.size() - 1) {
                    w.println(",");
                } else {
                    w.println();
                }
            }
            w.println("]");
        }
    }

    // =================================================================
    // Formatting helpers
    // =================================================================

    /**
     * Is the requested format JSON?
     *
     * <p>Everything that is not {@code json} is CSV: an export that
     * answers with an empty body because of a typo in the query
     * parameter would be worse than a CSV the reader did not ask for.
     */
    private boolean isJson(String format) {
        return format != null && "json".equalsIgnoreCase(format.trim());
    }

    /**
     * Quote a value for CSV when it needs it.
     *
     * <p>The same rule as the audit-log export: a value is quoted when
     * it carries a comma, a quote or a newline.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Join the headings of a cell into one CSV cell.
     *
     * <p>Headings are joined with {@code |} — a separator that cannot
     * occur in a markdown heading and that keeps the cell readable in a
     * spreadsheet.
     */
    private String joinHeadings(List<String> headings) {
        if (headings == null || headings.isEmpty()) {
            return "";
        }
        return String.join(" | ", headings);
    }

    /**
     * A JSON string literal, or {@code null} for a missing value.
     */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * A JSON array of strings.
     */
    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(jsonString(values.get(i)));
        }
        return sb.append(']').toString();
    }
}
