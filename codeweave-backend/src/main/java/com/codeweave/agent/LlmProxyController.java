package com.codeweave.agent;

import com.codeweave.config.DshProperties;
import com.codeweave.config.SecretCryptoService;
import com.codeweave.knowledge.RagflowRetrievalService;
import com.codeweave.settings.ModelConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * OpenAI-compatible proxy used by the native DSH provider. DSH keeps its
 * normal chat flow; this endpoint adds project knowledge before forwarding to
 * the user's configured model endpoint.
 */
@RestController
@RequestMapping("/api/agent/llm-proxy")
public class LlmProxyController {
  private static final String PROJECT_HASH = "cw:workspace:projects";
  private final DshProperties properties;
  private final ModelConfigRepository models;
  private final SecretCryptoService crypto;
  private final RagflowRetrievalService retrieval;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public LlmProxyController(DshProperties properties, ModelConfigRepository models, SecretCryptoService crypto,
                            RagflowRetrievalService retrieval, StringRedisTemplate redis, ObjectMapper mapper) {
    this.properties = properties; this.models = models; this.crypto = crypto; this.retrieval = retrieval;
    this.redis = redis; this.mapper = mapper;
  }

  @PostMapping({"", "/", "/chat/completions", "/v1/chat/completions", "/completions"})
  public ResponseEntity<byte[]> completions(@RequestParam String ownerId, @RequestParam String projectId,
                                             @RequestParam String modelId, @RequestParam String signature,
                                             @RequestBody JsonNode request) {
    return complete(ownerId, projectId, modelId, signature, request);
  }

  @PostMapping({"/{ownerId}/{projectId}/{modelId}/{signature}",
    "/{ownerId}/{projectId}/{modelId}/{signature}/chat/completions",
    "/{ownerId}/{projectId}/{modelId}/{signature}/v1/chat/completions",
    "/{ownerId}/{projectId}/{modelId}/{signature}/completions"})
  public ResponseEntity<byte[]> completionsPath(@PathVariable String ownerId, @PathVariable String projectId,
                                                 @PathVariable String modelId, @PathVariable String signature,
                                                 @RequestBody JsonNode request) {
    return complete(ownerId, projectId, modelId, signature, request);
  }

  private ResponseEntity<byte[]> complete(String ownerId, String projectId, String modelId, String signature, JsonNode request) {
    if (!validSignature(ownerId, projectId, modelId, signature)) throw new ResponseStatusException(FORBIDDEN, "Invalid DSH model proxy signature");
    requireProjectMember(ownerId, projectId);
    ModelConfigRepository.ModelConfig model = models.find(ownerId, modelId)
      .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "模型配置不存在"));
    if (model.encryptedApiKey() == null || model.encryptedApiKey().isBlank()) throw new ResponseStatusException(BAD_GATEWAY, "模型没有可用的 API Key");
    final String apiKey;
    try { apiKey = crypto.decrypt(model.encryptedApiKey()); }
    catch (Exception error) { throw new ResponseStatusException(BAD_GATEWAY, "模型 API Key 解密失败"); }

    ObjectNode payload = request != null && request.isObject() ? (ObjectNode) request.deepCopy() : mapper.createObjectNode();
    String question = latestUserText(payload.path("messages"));
    // This is the entry point used by the native DSH console. Use the full
    // retrieval path so document/list/chunk questions also reach RAGFlow;
    // calling search() here alone only performs semantic chunk retrieval and
    // leaves DSH to answer from its local workspace files.
    RagflowRetrievalService.RetrievalResult knowledge = retrieval.retrieveResult(projectId, ownerId, question);
    String context = knowledge.context();
    if (!context.isBlank() || !knowledge.message().isBlank()) {
      ArrayNode messages = payload.withArray("messages");
      ObjectNode system = mapper.createObjectNode();
      system.put("role", "system");
      String knowledgeInstruction = context.isBlank()
        ? "本次项目知识库检索未能提供资料，原因：" + knowledge.message() + "。请明确告知用户知识库没有被成功检索，不要声称已经查到项目资料。"
        : context + "\n\n请结合以上项目知识库资料回答用户问题；资料不足时明确说明，不要编造。";
      system.put("content", knowledgeInstruction);
      messages.insert(0, system);
    }

    try {
      String endpoint = model.endpoint().trim().replaceAll("/+$", "");
      if (!endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions";
      HttpRequest upstream = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(Duration.ofSeconds(120))
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.ACCEPT, "text/event-stream, application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
        .build();
      HttpResponse<byte[]> response = http.send(upstream, HttpResponse.BodyHandlers.ofByteArray());
      ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode());
      response.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(value -> builder.header(HttpHeaders.CONTENT_TYPE, value));
      response.headers().firstValue(HttpHeaders.CACHE_CONTROL).ifPresent(value -> builder.header(HttpHeaders.CACHE_CONTROL, value));
      response.headers().firstValue("x-request-id").ifPresent(value -> builder.header("x-request-id", value));
      return builder.body(response.body());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(BAD_GATEWAY, "模型请求被中断");
    } catch (Exception error) {
      throw new ResponseStatusException(BAD_GATEWAY, "无法连接配置的模型服务", error);
    }
  }

  private boolean validSignature(String ownerId, String projectId, String modelId, String signature) {
    String expected = DshConsoleAddress.proxySignature(properties, ownerId, projectId, modelId);
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), String.valueOf(signature).getBytes(StandardCharsets.UTF_8));
  }

  private void requireProjectMember(String ownerId, String projectId) {
    Object raw = redis.opsForHash().get(PROJECT_HASH, projectId);
    if (raw == null) throw new ResponseStatusException(NOT_FOUND, "项目不存在");
    try {
      Map<String, Object> project = mapper.readValue(String.valueOf(raw), Map.class);
      Object members = project.get("members");
      if (members instanceof Collection<?> collection) {
        for (Object item : collection) if (item instanceof Map<?, ?> member && ownerId.equals(String.valueOf(member.get("userId")))) return;
      }
      if (ownerId.equals(String.valueOf(project.getOrDefault("ownerId", ""))) || "demo-user".equals(ownerId)) return;
    } catch (Exception ignored) { }
    throw new ResponseStatusException(FORBIDDEN, "模型所属用户不是该项目成员");
  }

  private String latestUserText(JsonNode messages) {
    if (!messages.isArray()) return "";
    for (int i = messages.size() - 1; i >= 0; i--) {
      JsonNode message = messages.get(i);
      if (!"user".equalsIgnoreCase(message.path("role").asText())) continue;
      JsonNode content = message.path("content");
      if (content.isTextual()) return content.asText().trim();
      if (content.isArray()) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) if (item.has("text")) text.append(item.path("text").asText()).append('\n');
        return text.toString().trim();
      }
    }
    return "";
  }
}
