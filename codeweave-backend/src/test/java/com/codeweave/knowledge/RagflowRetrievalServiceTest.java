package com.codeweave.knowledge;

import com.codeweave.config.SecretCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class RagflowRetrievalServiceTest {
  @Test
  void documentQuestionFindsADeepDocumentAndReadsItsChunks() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var documentQuery = new AtomicReference<String>();
    server.createContext("/api/v1/datasets/ds/documents", exchange -> {
      documentQuery.set(exchange.getRequestURI().getRawQuery());
      respond(exchange, 200, "{\"code\":0,\"data\":{\"docs\":[{\"id\":\"doc-108\",\"name\":\"0420260203-108王润.docx\",\"run\":\"3\"}],\"total\":1}}");
    });
    server.createContext("/api/v1/retrieval", exchange -> {
      respond(exchange, 200, "{\"code\":0,\"data\":{\"chunks\":[]}}");
    });
    server.createContext("/api/v1/datasets/ds/documents/doc-108/chunks", exchange -> {
      respond(exchange, 200, "{\"code\":0,\"data\":{\"chunks\":[{\"content\":\"王润：这是第一个切片。\"},{\"content\":\"王润：这是第八个切片。\"}],\"total\":8}}");
    });
    server.start();
    try {
      var redis = mock(StringRedisTemplate.class);
      var hash = mock(HashOperations.class);
      when(redis.opsForHash()).thenReturn(hash);
      when(hash.get("cw:workspace:projects", "p")).thenReturn("{\"ragflowEnabled\":true,\"ragflowDatasetIds\":[\"ds\"],\"ragflowUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\",\"ragflowTokenCiphertext\":\"cipher\"}");
      var crypto = mock(SecretCryptoService.class);
      when(crypto.decrypt("cipher")).thenReturn("test-token");

      var result = new RagflowRetrievalService(redis, new ObjectMapper(), crypto)
        .retrieveResult("p", "u", "王润的聊天内容能看到么");

      assertTrue(result.context().contains("0420260203-108王润.docx"));
      assertTrue(result.context().contains("这是第一个切片"));
      assertTrue(result.context().contains("这是第八个切片"));
      assertTrue(documentQuery.get().contains("keywords=%E7%8E%8B%E6%B6%A6"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void keywordMissDoesNotFallBackToAnUnrelatedFirstPage() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/datasets/ds/documents", exchange ->
      respond(exchange, 200, "{\"code\":0,\"data\":{\"docs\":[],\"total\":0}}"));
    server.createContext("/api/v1/retrieval", exchange ->
      respond(exchange, 200, "{\"code\":0,\"data\":{\"chunks\":[]}}"));
    server.start();
    try {
      var redis = mock(StringRedisTemplate.class);
      var hash = mock(HashOperations.class);
      when(redis.opsForHash()).thenReturn(hash);
      when(hash.get("cw:workspace:projects", "p")).thenReturn("{\"ragflowEnabled\":true,\"ragflowDatasetIds\":[\"ds\"],\"ragflowUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\",\"ragflowTokenCiphertext\":\"cipher\"}");
      var crypto = mock(SecretCryptoService.class);
      when(crypto.decrypt("cipher")).thenReturn("test-token");

      var result = new RagflowRetrievalService(redis, new ObjectMapper(), crypto)
        .retrieveResult("p", "u", "不存在的人物聊天内容能看到么");

      assertTrue(result.context().contains("RAGFlow") && result.context().contains("匹配的文档"));
      assertTrue(!result.context().contains("以下内容来自项目绑定"));
    } finally {
      server.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    } catch (IOException error) {
      throw new RuntimeException(error);
    }
  }
}
