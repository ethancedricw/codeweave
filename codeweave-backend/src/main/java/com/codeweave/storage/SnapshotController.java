package com.codeweave.storage;

import cn.dev33.satoken.stp.StpUtil;
import com.codeweave.workspace.WorkspaceProvisioningService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;
import java.util.Collection;

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
    Object rawProject = redis.opsForHash().get("cw:workspace:projects", projectId);
    if (rawProject == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    try {
      Map<String, Object> project = mapper.readValue(String.valueOf(rawProject), new TypeReference<>() {});
      boolean member = String.valueOf(project.getOrDefault("ownerId", "")).equals(ownerId);
      Object members = project.get("members");
      if (members instanceof Collection<?> collection) {
        member = member || collection.stream().anyMatch(item -> item instanceof Map<?, ?> map && ownerId.equals(String.valueOf(map.get("userId"))));
      }
      if (!member) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该项目成员");
    } catch (ResponseStatusException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("项目状态读取失败", error);
    }
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
