package com.codeweave.storage;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.workspace.WorkspaceProvisioningService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace/projects/{projectId}/snapshots")
public class SnapshotController {
  private final MinioStorageService storage;
  private final WorkspaceProvisioningService provisioning;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public SnapshotController(MinioStorageService storage, WorkspaceProvisioningService provisioning, StringRedisTemplate redis, ObjectMapper mapper) { this.storage = storage; this.provisioning = provisioning; this.redis = redis; this.mapper = mapper; }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> upload(@PathVariable String projectId, @RequestPart("file") MultipartFile file) {
    StpUtil.checkLogin();
    String ownerId = StpUtil.getLoginIdAsString();
    MinioStorageService.StoredObject object = storage.upload(ownerId + "/" + projectId + "/snapshots", file);
    Map<String, Object> workspace = provisioning.materializeSnapshot(projectId, file);
    Object raw = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (raw != null) {
      try {
        Map<String, Object> project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        project.putAll(workspace);
        project.put("updatedAt", Instant.now().toString());
        redis.opsForHash().put("cw:workspace:projects", projectId, mapper.writeValueAsString(project));
      } catch (Exception error) { throw new IllegalStateException("项目工作区状态保存失败", error); }
    }
    Map<String, Object> response = new java.util.LinkedHashMap<>();
    response.putAll(workspace);
    response.putAll(Map.of(
      "projectId", projectId,
      "objectName", object.objectName(),
      "originalName", object.originalName(),
      "contentType", object.contentType() == null ? "application/octet-stream" : object.contentType(),
      "size", object.size(),
      "downloadUrl", object.downloadUrl(),
      "uploadedAt", Instant.now().toString()
    ));
    return response;
  }
}
