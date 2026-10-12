package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.PlatformDocTypePolicyResponse;
import com.livingdocs.modules.admin.dto.PlatformSettingsResponse;
import com.livingdocs.modules.admin.dto.UpdatePlatformDocTypePolicyRequest;
import com.livingdocs.modules.admin.dto.UpdatePlatformSettingsRequest;
import com.livingdocs.modules.admin.model.DocOutputFormat;
import com.livingdocs.modules.admin.model.GenerationTrigger;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy.Workflow;
import com.livingdocs.modules.admin.model.PlatformSettings;
import com.livingdocs.modules.admin.repository.PlatformDocTypePolicyRepository;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Manages the singleton {@code platform_settings} row and the
 * {@code platform_doc_type_policies} table. These are the defaults
 * that new workspaces copy from.
 */
@Service
public class PlatformSettingsService {

    private static final Set<String> VALID_DRIFT_THRESHOLDS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> VALID_MERGE_POLICIES = Set.of("WARN", "BLOCK");
    private static final Set<String> VALID_OUTPUT_FORMATS = Set.of("MARKDOWN", "HTML", "PDF");
    private static final Set<String> VALID_GENERATION_TRIGGERS =
            Set.of("MANUAL", "ON_COMMIT", "ON_PULL_REQUEST", "ON_MERGE", "SCHEDULED");

    private final PlatformSettingsRepository settingsRepository;
    private final PlatformDocTypePolicyRepository docTypeRepository;
    private final DocTemplateRepository templateRepository;

    public PlatformSettingsService(PlatformSettingsRepository settingsRepository,
                                    PlatformDocTypePolicyRepository docTypeRepository,
                                    DocTemplateRepository templateRepository) {
        this.settingsRepository = settingsRepository;
        this.docTypeRepository = docTypeRepository;
        this.templateRepository = templateRepository;
    }

    @Transactional(readOnly = true)
    public PlatformSettingsResponse getSettings() {
        return PlatformSettingsResponse.from(settingsRepository.getSingleton());
    }

    @Transactional
    public PlatformSettingsResponse updateSettings(UpdatePlatformSettingsRequest req) {
        if (!VALID_DRIFT_THRESHOLDS.contains(req.defaultDriftSeverityThreshold())) {
            throw new IllegalArgumentException(
                    "Invalid defaultDriftSeverityThreshold: " + req.defaultDriftSeverityThreshold());
        }
        if (!VALID_MERGE_POLICIES.contains(req.defaultMergePolicyCritical())) {
            throw new IllegalArgumentException(
                    "Invalid defaultMergePolicyCritical: " + req.defaultMergePolicyCritical());
        }
        if (!VALID_OUTPUT_FORMATS.contains(req.defaultOutputFormat())) {
            throw new IllegalArgumentException(
                    "Invalid defaultOutputFormat: " + req.defaultOutputFormat());
        }
        if (!VALID_GENERATION_TRIGGERS.contains(req.defaultGenerationTrigger())) {
            throw new IllegalArgumentException(
                    "Invalid defaultGenerationTrigger: " + req.defaultGenerationTrigger());
        }
        // The default template, when set, must exist and be a GLOBAL template
        // (workspace_id IS NULL). We surface a 404 in that case so the UI can
        // clear its stale selection.
        if (req.defaultTemplateId() != null) {
            var tpl = templateRepository.findById(req.defaultTemplateId())
                    .orElseThrow(() -> new NotFoundException(
                            "Default template not found: " + req.defaultTemplateId()));
            if (tpl.getWorkspaceId() != null) {
                throw new IllegalArgumentException(
                        "Default template must be a global template, not a workspace-scoped one");
            }
        }

        PlatformSettings s = settingsRepository.getSingleton();
        s.setDefaultAutoUpdateOnCommit(req.defaultAutoUpdateOnCommit());
        s.setDefaultAutoUpdateOnPr(req.defaultAutoUpdateOnPr());
        s.setDefaultAutoUpdateOnMerge(req.defaultAutoUpdateOnMerge());
        s.setDefaultDriftSeverityThreshold(req.defaultDriftSeverityThreshold());
        s.setDefaultRequireManagerApproval(req.defaultRequireManagerApproval());
        s.setDefaultMergePolicyCritical(req.defaultMergePolicyCritical());
        s.setDefaultAiConfidenceThreshold(req.defaultAiConfidenceThreshold());
        s.setDefaultOutputFormat(DocOutputFormat.valueOf(req.defaultOutputFormat()));
        s.setDefaultTemplateId(req.defaultTemplateId());
        s.setDefaultGenerationTrigger(GenerationTrigger.valueOf(req.defaultGenerationTrigger()));
        return PlatformSettingsResponse.from(settingsRepository.save(s));
    }

    @Transactional(readOnly = true)
    public List<PlatformDocTypePolicyResponse> listDocTypePolicies() {
        return docTypeRepository.findAllByOrderByDocTypeAsc().stream()
                .map(PlatformDocTypePolicyResponse::from)
                .toList();
    }

    @Transactional
    public PlatformDocTypePolicyResponse upsertDocTypePolicy(String docType,
                                                              UpdatePlatformDocTypePolicyRequest req) {
        Workflow wf;
        try {
            wf = Workflow.valueOf(req.workflow());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid workflow: " + req.workflow());
        }
        PlatformDocTypePolicy p = docTypeRepository.findByDocType(docType)
                .orElseGet(() -> new PlatformDocTypePolicy(docType, wf));
        p.setWorkflow(wf);
        return PlatformDocTypePolicyResponse.from(docTypeRepository.save(p));
    }

    @Transactional
    public void deleteDocTypePolicy(String docType) {
        PlatformDocTypePolicy p = docTypeRepository.findByDocType(docType)
                .orElseThrow(() -> new NotFoundException("Doc-type policy not found: " + docType));
        docTypeRepository.delete(p);
    }
}
