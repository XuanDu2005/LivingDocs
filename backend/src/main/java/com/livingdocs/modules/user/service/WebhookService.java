package com.livingdocs.modules.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.model.GithubConnection;
import com.livingdocs.modules.github.repository.GithubConnectionRepository;
import com.livingdocs.modules.ai.client.AiDtos.SourceFile;
import com.livingdocs.modules.ai.service.AiDocumentationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private final ObjectMapper objectMapper;
    private final CodeDocumentLinkRepository linkRepository;
    private final CodeEntityRepository codeEntityRepository;
    private final GithubClient githubClient;
    private final GithubConnectionRepository githubConnectionRepo;
    private final AiDocumentationService aiDocumentationService;

    public WebhookService(ObjectMapper objectMapper, 
                          CodeDocumentLinkRepository linkRepository,
                          CodeEntityRepository codeEntityRepository,
                          GithubClient githubClient,
                          GithubConnectionRepository githubConnectionRepo,
                          AiDocumentationService aiDocumentationService) {
        this.objectMapper = objectMapper;
        this.linkRepository = linkRepository;
        this.codeEntityRepository = codeEntityRepository;
        this.githubClient = githubClient;
        this.githubConnectionRepo = githubConnectionRepo;
        this.aiDocumentationService = aiDocumentationService;
    }

    public void processGitHubPayload(String eventType, String payload) throws Exception {
        if (!"push".equals(eventType)) {
            return;
        }

        JsonNode root = objectMapper.readTree(payload);
        String repoFullName = root.path("repository").path("full_name").asText();
        String branch = root.path("ref").asText().replace("refs/heads/", "");
        
        UUID systemUserId = UUID.randomUUID(); // Mock User cho hệ thống tự động chạy

        Set<String> changedFiles = new HashSet<>();
        JsonNode commits = root.path("commits");
        if (commits.isArray()) {
            for (JsonNode commit : commits) {
                commit.path("added").forEach(n -> changedFiles.add(n.asText()));
                commit.path("modified").forEach(n -> changedFiles.add(n.asText()));
            }
        }

        if (changedFiles.isEmpty()) {
            log.info("Không có file code nào được thêm hoặc sửa. Bỏ qua.");
            return;
        }

        log.info(" BƯỚC 1: Phân tích Webhook. Repository [{}], Branch [{}]. Files thay đổi: {}", repoFullName, branch, changedFiles);

        // BƯỚC 2: TÌM TÀI LIỆU BỊ ẢNH HƯỞNG
        List<CodeEntity> affectedEntities = codeEntityRepository.findByFilePathIn(changedFiles);
        if (affectedEntities.isEmpty()) {
            log.info(" Các file code này chưa được index thành CodeEntity. Bỏ qua.");
            return; // Đã khôi phục lệnh return
        }

        Set<UUID> entityIds = affectedEntities.stream().map(CodeEntity::getId).collect(Collectors.toSet());
        List<CodeDocumentLink> affectedLinks = linkRepository.findByCodeEntityIdIn(entityIds);

        if (affectedLinks.isEmpty()) {
            log.info(" Các file code này hiện chưa gắn với tài liệu nào. Không cần cập nhật Docs.");
            return; // Đã khôi phục lệnh return
        } 

        String[] repoParts = repoFullName.split("/");
        String owner = repoParts.length == 2 ? repoParts[0] : "";
        String repoName = repoParts.length == 2 ? repoParts[1] : "";

        // Lấy Token thực tế từ Database
        String accessToken = getValidAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            log.warn(" Không tìm thấy Access Token hợp lệ trong Database. Hủy luồng kéo code.");
            return; // Nếu user chưa connect Github thì dừng lại
        }

        // BƯỚC 3: KÉO CODE VỀ VÀ CHUẨN BỊ SOURCE CHO AI
        List<SourceFile> downloadedFiles = new ArrayList<>();
        
        for (String filePath : changedFiles) {
            log.info("📥 Đang kéo code mới của file [{}]...", filePath);
            try {
                String fileContent = githubClient.getFileContent(accessToken, owner, repoName, filePath, branch);
                if (fileContent != null && !fileContent.isBlank()) {
                    downloadedFiles.add(new SourceFile(filePath, fileContent));
                    log.info(" Kéo thành công [{}].", filePath);
                }
            } catch (Exception e) {
                log.error(" Lỗi kéo file [{}]: {}", filePath, e.getMessage());
            }
        }

        if (downloadedFiles.isEmpty()) {
            log.warn("Không kéo được file nào. Hủy luồng sinh AI.");
            return;
        }

        // BƯỚC 4: GỌI AI ĐỂ SINH VÀ CẬP NHẬT TÀI LIỆU
        log.info(" Gửi {} file cho AI xử lý...", downloadedFiles.size());
        
        UUID docId = affectedLinks.get(0).getDocumentId();
        
        try {
            // Sử dụng hàm có sẵn của dự án để AI xử lý, tự động tạo version mới và đánh dấu Pending Review
            aiDocumentationService.generateForDocument(systemUserId, docId, downloadedFiles, "MODULE_GUIDE");
            log.info(" AI đã xử lý xong và tạo phiếu chờ review cho Document ID: {}", docId);
        } catch (Exception e) {
            log.error(" Gọi AI Service thất bại: {}", e.getMessage());
        }
    }

    /**
     * Lấy token GitHub đang Active từ Database một cách chuẩn xác.
     */
    private String getValidAccessToken() {
        try {
            List<GithubConnection> activeConns = githubConnectionRepo.findAllByStatus(GithubConnection.Status.ACTIVE);
            if (!activeConns.isEmpty()) {
                return activeConns.get(0).getAccessToken();
            }
            return null;
        } catch (Exception ex) {
            log.warn(" Lỗi khi tìm AccessToken: {}", ex.getMessage());
            return null;
        }
    }
}