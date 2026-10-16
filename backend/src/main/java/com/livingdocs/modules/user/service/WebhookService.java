package com.livingdocs.modules.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private final ObjectMapper objectMapper;

    public WebhookService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void processGitHubPayload(String eventType, String payload) throws Exception {
        JsonNode rootNode = objectMapper.readTree(payload);

        if ("push".equals(eventType)) {
            handlePushEvent(rootNode);
        } else if ("issues".equals(eventType)) {
            log.info("Sự kiện Issues nhận được (Đang chờ xử lý)");
        } else if ("ping".equals(eventType)) {
            log.info("Nhận được ping từ GitHub. Kết nối ổn định!");
        } else {
            log.info("Bỏ qua sự kiện không hỗ trợ: {}", eventType);
        }
    }

    private void handlePushEvent(JsonNode payload) {
        String repoName = payload.path("repository").path("full_name").asText();
        String pusherName = payload.path("pusher").path("name").asText();
        String branch = payload.path("ref").asText();

        log.info("Phát hiện code mới! [{}] vừa push lên nhánh [{}] của kho [{}]", pusherName, branch, repoName);

        JsonNode commits = payload.path("commits");
        if (commits != null && commits.isArray()) {
            for (JsonNode commit : commits) {
                String commitMessage = commit.path("message").asText();
                String commitId = commit.path("id").asText();
                String shortId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
                
                log.info("Commit ({}): {}", shortId, commitMessage);

                logFiles(commit, "added", "Thêm mới");
                logFiles(commit, "modified", "Chỉnh sửa");
                logFiles(commit, "removed", "Đã xóa");
            }
        }
    }

    private void logFiles(JsonNode commit, String fieldName, String label) {
        JsonNode files = commit.path(fieldName);
        if (files != null && files.isArray() && files.size() > 0) {
            for (JsonNode file : files) {
                log.info("    {}: {}", label, file.asText());
            }
        }
    }
}