package com.codeweave.knowledge;

import com.codeweave.config.SecretCryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retrieves project-bound RAGFlow context for Agent prompts. */
@Service
public class RagflowRetrievalService {
  private static final Logger log = LoggerFactory.getLogger(RagflowRetrievalService.class);
  private static final String PROJECT_HASH = "cw:workspace:projects";
  private static final String CONFIG_PREFIX = "cw:settings:ragflow:";
  // A document question is expected to include all parsed chunks of the
  // matched file. Keep a safety ceiling for very large files, but do not cut
  // ordinary multi-chunk interview documents at a small 7k/9k boundary.
  private static final int MAX_DOCUMENT_CHARS = 24000;
  private static final int MAX_DOCUMENT_CONTEXT_CHARS = 32000;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final SecretCryptoService crypto;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

  public RagflowRetrievalService(StringRedisTemplate redis, ObjectMapper mapper, SecretCryptoService crypto) {
    this.redis = redis;
    this.mapper = mapper;
    this.crypto = crypto;
  }

  public String retrieve(String projectId, String userId, String question) {
    return retrieveResult(projectId, userId, question).context();
  }

  /**
   * Performs the normal semantic retrieval once and, for document-oriented
   * questions, appends the project-bound document API result. Keeping this as
   * a result object lets the native DSH proxy preserve the useful diagnostic
   * message when RAGFlow returns no chunks.
   */
  public RetrievalResult retrieveResult(String projectId, String userId, String question) {
    RetrievalResult result = search(projectId, userId, question);
    String context = result.context();
    if (!documentQuestion(question)) return result;
    String documents = documentContext(projectId, userId, question, documentContentQuestion(question));
    if (documents.isBlank()) return result;
    if (context.isBlank()) return new RetrievalResult(documents, result.items(), "");
    return new RetrievalResult(context + "\n\n" + documents, result.items(), "");
  }

  /**
   * Handles document-oriented questions inside the normal Agent prompt. The
   * project binding is applied before every RAGFlow request, so a user cannot
   * ask the Agent to enumerate or read an unbound dataset.
   */
  private String documentContext(String projectId, String userId, String question, boolean includeContent) {
    try {
      Map<String, Object> project = boundProject(projectId);
      if (!Boolean.TRUE.equals(project.get("ragflowEnabled"))) return "";
      List<String> datasetIds = values(project.get("ragflowDatasetIds"));
      if (datasetIds.isEmpty()) return "";
      RagflowAccess access = access(projectId, userId, project);
      if (access == null) return "";
      List<DocumentRef> documents = new ArrayList<>();
      int totalDocuments = 0;
      for (String datasetId : datasetIds) {
        DocumentPage page = listDocuments(access, datasetId, question);
        documents.addAll(page.documents());
        totalDocuments += page.total();
      }
      String lookupKeyword = documentLookupKeyword(question);
      if (totalDocuments == 0 && !lookupKeyword.isBlank()) {
        return "在项目绑定的 RAGFlow 知识库中没有找到与“" + lookupKeyword + "”匹配的文档。";
      }
      if (totalDocuments == 0) return "项目绑定的 RAGFlow 知识库中没有文档。";
      List<DocumentRef> selected = selectDocuments(documents, question);
      if (includeContent && !selected.isEmpty()) {
        List<String> contents = new ArrayList<>();
        for (DocumentRef document : selected.stream().limit(2).toList()) {
          String content = documentContent(access, document);
          if (!content.isBlank()) contents.add("文档：" + document.name() + "\n" + content);
        }
        if (!contents.isEmpty()) return "以下内容来自项目绑定的 RAGFlow 文档：\n" + truncate(String.join("\n\n", contents), MAX_DOCUMENT_CONTEXT_CHARS);
        return "已找到匹配文档，但该文档没有可读取的解析切片；请检查 RAGFlow 的解析状态。";
      }
      if (includeContent && selected.isEmpty() && !lookupKeyword.isBlank()) {
        return "已查询项目绑定的 RAGFlow 知识库，但没有找到与“" + lookupKeyword + "”匹配的文档。";
      }
      List<String> lines = documents.stream().limit(80).map(document -> {
        String status = document.status().isBlank() ? "" : "，状态：" + document.status();
        return "- " + document.name() + (document.datasetName().isBlank() ? "" : "（知识库：" + document.datasetName() + "）") + status;
      }).toList();
      String more = totalDocuments > lines.size() ? "\n当前仅列出前 " + lines.size() + " 个文档；可提供文件名以查询指定文档。" : "";
      return "项目绑定的 RAGFlow 知识库共有 " + totalDocuments + " 个文档。\n" + String.join("\n", lines) + more;
    } catch (Exception error) {
      log.warn("RAGFlow document lookup failed for project {}: {}", projectId, error.getMessage());
      return "RAGFlow 文档查询失败，请检查知识库连接和文档解析状态。";
    }
  }

  private DocumentPage listDocuments(RagflowAccess access, String datasetId, String question) throws Exception {
    String keyword = documentLookupKeyword(question);
    String query = "?page=1&page_size=100" + (keyword.isBlank() ? "" : "&keywords=" + encode(keyword));
    URI endpoint = URI.create(access.baseUrl() + "/datasets/" + encode(datasetId) + "/documents" + query);
    JsonNode root = get(access, endpoint);
    JsonNode data = root.path("data");
    JsonNode rows = data.path("docs");
    if (!rows.isArray()) rows = data.path("documents");
    if (!rows.isArray()) rows = data.isArray() ? data : root.path("documents");
    if (!rows.isArray()) throw new IllegalStateException("RAGFlow 文档列表响应缺少 docs");
    List<DocumentRef> result = new ArrayList<>();
    for (JsonNode row : rows) {
      String id = firstText(row, "id", "document_id", "doc_id");
      String name = firstText(row, "name", "doc_name", "document_name", "title");
      if (id.isBlank() && name.isBlank()) continue;
      result.add(new DocumentRef(datasetId, access.datasetName(datasetId), id, name.isBlank() ? id : name,
        firstText(row, "run", "status", "parse_status"), row));
    }
    int total = data.path("total").asInt(0);
    if (total <= 0) total = data.path("total_count").asInt(0);
    if (total <= 0) total = root.path("total").asInt(result.size());
    if (total <= 0) total = result.size();
    return new DocumentPage(result, Math.max(total, result.size()));
  }

  private String documentContent(RagflowAccess access, DocumentRef document) throws Exception {
    if (document.id().isBlank()) return "";
    URI chunksEndpoint = URI.create(access.baseUrl() + "/datasets/" + encode(document.datasetId()) + "/documents/" + encode(document.id()) + "/chunks?page=1&page_size=80");
    JsonNode root = get(access, chunksEndpoint);
    JsonNode data = root.path("data");
    JsonNode chunks = data.path("chunks");
    if (!chunks.isArray()) chunks = data.isArray() ? data : root.path("chunks");
    List<String> parts = new ArrayList<>();
    if (chunks.isArray()) for (JsonNode chunk : chunks) {
      String text = firstText(chunk, "content", "highlight", "text");
      if (!text.isBlank()) parts.add(text.trim());
    }
    if (!parts.isEmpty()) return truncate(String.join("\n\n", parts), MAX_DOCUMENT_CHARS);
    // RAGFlow's document detail endpoint downloads the original file. It is
    // not a JSON document-content endpoint, so do not treat it as a fallback.
    return "";
  }

  private JsonNode get(RagflowAccess access, URI endpoint) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
      .header("Authorization", "Bearer " + access.token()).header("Accept", "application/json").GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException("RAGFlow 返回 HTTP " + response.statusCode());
    JsonNode root = mapper.readTree(response.body());
    if (root.has("code") && root.path("code").asInt(0) != 0) {
      throw new IllegalStateException("RAGFlow 返回业务错误 " + root.path("code").asInt() + ": " + root.path("message").asText("未知错误"));
    }
    return root;
  }

  private List<DocumentRef> selectDocuments(List<DocumentRef> documents, String question) {
    String normalized = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
    List<DocumentRef> matched = documents.stream().filter(document -> !document.name().isBlank() && normalized.contains(document.name().toLowerCase(java.util.Locale.ROOT))).toList();
    if (!matched.isEmpty()) return matched;
    matched = documents.stream().filter(document -> {
      String base = document.name().toLowerCase(java.util.Locale.ROOT).replaceAll("\\.[a-z0-9]+$", "");
      return textTokens(base).stream().anyMatch(token -> token.length() >= 2 && normalized.contains(token));
    }).toList();
    return matched;
  }

  /** Extract meaningful Chinese or alphanumeric fragments from a document name. */
  private List<String> textTokens(String value) {
    List<String> tokens = new ArrayList<>();
    var matcher = java.util.regex.Pattern.compile("[\\p{IsHan}]{2,}|[a-z0-9][a-z0-9_-]{2,}").matcher(value == null ? "" : value);
    while (matcher.find()) tokens.add(matcher.group());
    return tokens;
  }

  private String documentLookupKeyword(String question) {
    if (question == null || question.isBlank()) return "";
    java.util.Set<String> ignored = java.util.Set.of("知识库", "文档", "文件", "资料", "切片", "分块", "内容", "全文", "原文", "聊天", "访谈", "对话", "采访", "查看", "打开", "读取", "能看到", "看看", "查一下", "这个", "项目", "绑定", "哪些", "某个", "有没有", "里面", "可以", "看到");
    String cleaned = question.replaceFirst("^(请问|请|帮我|能不能|可以|查看|打开|读取|看看|查一下|查询)+", "");
    for (String token : textTokens(cleaned)) {
      if (!token.matches("[\\p{IsHan}]+")) {
        if (token.length() >= 3 && !ignored.contains(token)) return token;
        continue;
      }
      for (int i = 0; i + 2 <= token.length(); i++) {
        String candidate = token.substring(i, i + 2);
        if (!ignored.contains(candidate)) return candidate;
      }
    }
    return "";
  }

  private boolean documentQuestion(String question) {
    if (question == null) return false;
    return question.matches("(?s).*(知识库|文档|文件|资料|切片|分块|chunk|chunks|聊天|访谈|对话|采访).*(有哪些|有几个|列表|目录|文件名|名称|状态|解析|内容|全文|原文|切片|分块|查看|打开|读取|能看到|看看|查一下).*|.*(有哪些|列出|列一下|查看|打开|读取|能看到|看看|查一下).*(知识库|文档|文件|资料|切片|分块|chunk|chunks|聊天|访谈|对话|采访).*|.*(查看|打开|读取|内容|全文|原文|切片|分块|chunk|chunks|聊天|访谈|对话|采访).*[.]((pdf)|(docx?)|(xlsx?)|(pptx?)|txt).*");
  }

  private boolean documentContentQuestion(String question) {
    return question != null && question.matches("(?s).*(查看|打开|读取|内容|全文|原文|解析结果|详细内容|切片|分块|chunk|chunks|聊天|访谈|对话|采访|能看到|看看|查一下).*(文档|文件|资料|知识库|[.]((pdf)|(docx?)|(xlsx?)|(pptx?)|txt)|聊天|访谈|对话|采访).*|.*(文档|文件|资料|聊天|访谈|对话|采访).*(内容|全文|原文|解析结果|切片|分块|chunk|chunks|能看到|看看|查一下).*");
  }

  private Map<String, Object> boundProject(String projectId) {
    return parse(redis.opsForHash().get(PROJECT_HASH, projectId));
  }

  private RagflowAccess access(String projectId, String userId, Map<String, Object> project) {
    String projectUrl = String.valueOf(project.getOrDefault("ragflowUrl", "")).trim();
    String projectCiphertext = String.valueOf(project.getOrDefault("ragflowTokenCiphertext", "")).trim();
    if (!projectUrl.isBlank() && !projectCiphertext.isBlank()) {
      RagflowAccess configured = buildAccess(projectUrl, projectCiphertext);
      if (configured != null) return configured;
    }
    List<String> owners = new ArrayList<>();
    String projectOwner = String.valueOf(project.getOrDefault("ownerId", "")).trim();
    if (!projectOwner.isBlank()) owners.add(projectOwner);
    if (userId != null && !userId.isBlank()) owners.add(userId);
    // Existing projects may not have an ownerId or project-scoped token.
    // Keep the administrator configuration as a compatibility source while
    // dataset_ids still enforce the project boundary.
    owners.add("demo-user");
    for (String owner : owners.stream().distinct().toList()) {
      Map<String, Object> config = parse(redis.opsForValue().get(CONFIG_PREFIX + owner));
      RagflowAccess configured = buildAccess(String.valueOf(config.getOrDefault("url", "")), String.valueOf(config.getOrDefault("tokenCiphertext", "")));
      if (configured != null) return configured;
    }
    return null;
  }

  private RagflowAccess buildAccess(String url, String ciphertext) {
    url = url == null ? "" : url.trim();
    ciphertext = ciphertext == null ? "" : ciphertext.trim();
    if (url.isBlank() || ciphertext.isBlank()) return null;
    try {
      String base = url.replaceAll("/+$", "");
      if (base.endsWith("/datasets")) base = base.substring(0, base.length() - "/datasets".length());
      if (!base.endsWith("/api/v1")) base += "/api/v1";
      return new RagflowAccess(base, crypto.decrypt(ciphertext));
    } catch (Exception ignored) { return null; }
  }

  private String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
  private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "\n[内容已截断]"; }
  private record DocumentRef(String datasetId, String datasetName, String id, String name, String status, JsonNode raw) {}
  private record DocumentPage(List<DocumentRef> documents, int total) {}
  private record RagflowAccess(String baseUrl, String token) {
    String datasetName(String id) { return id; }
  }

  /**
   * Search the project's bound datasets and keep the raw useful metadata for
   * callers such as the native DSH collaboration panel.
   */
  public RetrievalResult search(String projectId, String userId, String question) {
    if (projectId == null || projectId.isBlank() || question == null || question.isBlank()) return RetrievalResult.empty("项目和问题不能为空");
    try {
      Map<String, Object> project = parse(redis.opsForHash().get(PROJECT_HASH, projectId));
      // The project binding is the security boundary. Never fall back to all
      // datasets visible to the user's RAGFlow account.
      if (!Boolean.TRUE.equals(project.get("ragflowEnabled"))) {
        log.info("RAGFlow skipped: project {} is disabled", projectId);
        return RetrievalResult.empty("项目未启用知识库");
      }
      List<String> datasetIds = values(project.get("ragflowDatasetIds"));
      if (datasetIds.isEmpty()) {
        log.warn("RAGFlow skipped: project {} has no bound datasets", projectId);
        return RetrievalResult.empty("项目未绑定知识库");
      }
      RagflowAccess access = access(projectId, userId, project);
      if (access == null) {
        log.warn("RAGFlow skipped: no usable project or user credentials for project {}", projectId);
        return RetrievalResult.empty("项目尚未配置可用的 RAGFlow 连接");
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("question", question.trim());
      body.put("dataset_ids", datasetIds);
      body.put("page", 1);
      body.put("page_size", 8);
      body.put("top_k", 8);
      body.put("similarity_threshold", 0.2);
      body.put("vector_similarity_weight", 0.3);
      body.put("keyword", false);
      body.put("highlight", false);
      HttpRequest request = HttpRequest.newBuilder(URI.create(retrievalEndpoint(access.baseUrl())))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + access.token())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        log.warn("RAGFlow retrieval returned HTTP {} for project {}", response.statusCode(), projectId);
        return RetrievalResult.empty("RAGFlow 返回 HTTP " + response.statusCode());
      }
      JsonNode root = mapper.readTree(response.body());
      if (root.has("code") && root.path("code").asInt(0) != 0) {
        return RetrievalResult.empty("RAGFlow 返回业务错误：" + root.path("message").asText("未知错误"));
      }
      return parseResults(root);
    } catch (Exception error) {
      log.warn("RAGFlow retrieval failed for project {}: {}", projectId, error.getMessage());
      return RetrievalResult.empty("RAGFlow 检索失败");
    }
  }

  private RetrievalResult parseResults(JsonNode root) {
    JsonNode data = root.path("data");
    JsonNode chunks = data.path("chunks");
    if (!chunks.isArray()) chunks = data.isArray() ? data : root.path("chunks");
    if (!chunks.isArray() || chunks.isEmpty()) return RetrievalResult.empty("没有找到相关内容");
    List<String> lines = new ArrayList<>();
    List<Map<String, Object>> items = new ArrayList<>();
    int total = 0;
    for (JsonNode chunk : chunks) {
      String content = firstText(chunk, "highlight", "content", "text");
      if (content.isBlank()) continue;
      String source = firstText(chunk, "doc_name", "document_name", "document_id");
      double score = chunk.has("similarity") ? chunk.path("similarity").asDouble(0) : chunk.path("score").asDouble(0);
      String line = source.isBlank() ? content : "来源：" + source + "\n" + content;
      if (total + line.length() > 6000) break;
      lines.add("- " + line.trim());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("content", content.trim());
      if (!source.isBlank()) item.put("source", source.trim());
      item.put("score", score);
      items.add(item);
      total += line.length();
      if (lines.size() >= 6) break;
    }
    if (lines.isEmpty()) return RetrievalResult.empty("没有找到相关内容");
    return new RetrievalResult("当前问题相关的 RAGFlow 知识库检索结果（请以资料为依据，并在不确定时明确说明）：\n" + String.join("\n", lines), items, "");
  }

  public record RetrievalResult(String context, List<Map<String, Object>> items, String message) {
    static RetrievalResult empty(String message) { return new RetrievalResult("", List.of(), message); }
  }

  private String firstText(JsonNode node, String... fields) {
    for (String field : fields) if (node.hasNonNull(field) && !node.path(field).asText().isBlank()) return node.path(field).asText();
    return "";
  }

  private List<String> values(Object raw) {
    if (!(raw instanceof Collection<?> collection)) return List.of();
    return collection.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).distinct().limit(32).toList();
  }

  private Map<String, Object> parse(Object raw) {
    if (raw == null) return Map.of();
    try { return mapper.readValue(String.valueOf(raw), new TypeReference<>() {}); }
    catch (Exception ignored) { return Map.of(); }
  }

  private String retrievalEndpoint(String url) {
    String base = url.trim().replaceAll("/+$", "");
    if (base.endsWith("/api/v1/datasets")) return base.substring(0, base.length() - "/datasets".length()) + "/retrieval";
    if (base.endsWith("/datasets")) return base.substring(0, base.length() - "/datasets".length()) + "/retrieval";
    return base.endsWith("/api/v1") ? base + "/retrieval" : base + "/api/v1/retrieval";
  }
}
