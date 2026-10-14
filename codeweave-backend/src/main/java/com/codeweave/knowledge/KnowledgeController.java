package com.codeweave.knowledge;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Project-scoped knowledge bridge used by the native DSH collaboration rail. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
  private static final String PROJECT_HASH = "cw:workspace:projects";
  private final RagflowRetrievalService retrieval;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public KnowledgeController(RagflowRetrievalService retrieval, StringRedisTemplate redis, ObjectMapper mapper) {
    this.retrieval = retrieval;
    this.redis = redis;
    this.mapper = mapper;
  }

  @PostMapping("/search")
  public Map<String, Object> search(@RequestBody Map<String, Object> request) {
    StpUtil.checkLogin();
    String projectId = String.valueOf(request.getOrDefault("projectId", "")).trim();
    String question = String.valueOf(request.getOrDefault("question", "")).trim();
    if (projectId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId 不能为空");
    if (question.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "检索内容不能为空");
    if (question.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "检索内容不能超过 2000 个字符");
    requireMember(projectId);
    RagflowRetrievalService.RetrievalResult result = retrieval.retrieveResult(projectId, StpUtil.getLoginIdAsString(), question);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("items", result.items());
    response.put("context", result.context());
    response.put("message", result.message());
    response.put("matched", !result.items().isEmpty());
    return response;
  }

  @GetMapping("/search")
  public Map<String, Object> searchGet(@RequestParam String projectId, @RequestParam String question) {
    return search(Map.of("projectId", projectId, "question", question));
  }

  private void requireMember(String projectId) {
    Object raw = redis.opsForHash().get(PROJECT_HASH, projectId);
    if (raw == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    Map<String, Object> project;
    try { project = mapper.readValue(String.valueOf(raw), new TypeReference<>() {}); }
    catch (Exception error) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "项目数据格式错误"); }
    String userId = StpUtil.getLoginIdAsString();
    Object members = project.get("members");
    if (members instanceof Collection<?> collection) {
      for (Object item : collection) {
        if (item instanceof Map<?, ?> member && userId.equals(String.valueOf(member.get("userId")))) return;
      }
    }
    if (userId.equals(String.valueOf(project.getOrDefault("ownerId", ""))) || "demo-user".equals(userId)) return;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是该项目成员");
  }
}
