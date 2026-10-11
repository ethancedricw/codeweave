package com.codeweave.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DshProcessManager {
  private static final Logger log = LoggerFactory.getLogger(DshProcessManager.class);
  private static final Pattern READY_URL = Pattern.compile("dsh web:\\s+\\S+\\?token=([^\\s]+)");

  private final DshProperties properties;
  private final CountDownLatch ready = new CountDownLatch(1);
  private volatile Process process;

  public DshProcessManager(DshProperties properties) { this.properties = properties; }

  @PostConstruct
  public void start() {
    if (!properties.isAutoStart()) return;
    if (isRuntimeListening() && (properties.getAuthToken() == null || properties.getAuthToken().isBlank())) {
      log.warn("DSH is already listening at {} but no auth-token is configured; leave auto-started process disabled or provide the current token", properties.getBaseUrl());
      ready.countDown();
      return;
    }
    String directory = properties.getWorkingDirectory();
    if (directory == null || directory.isBlank() || !Files.isDirectory(Path.of(directory))) {
      log.warn("DSH auto-start is enabled but working-directory does not exist: {}", directory);
      return;
    }
    try {
      List<String> command = new ArrayList<>();
      command.add(resolveNodeCommand(properties.getNodeCommand()));
      command.addAll(properties.getLaunchArgs());
      ProcessBuilder builder = new ProcessBuilder(command).directory(Path.of(directory).toFile()).redirectErrorStream(true);
      process = builder.start();
      Process current = process;
      Thread output = new Thread(() -> readOutput(current), "codeweave-dsh-output");
      output.setDaemon(true);
      output.start();
      if (!ready.await(Math.max(1, properties.getStartupTimeoutSeconds()), TimeUnit.SECONDS)) {
        log.warn("DSH started but did not publish an authenticated URL before the startup timeout");
      }
    } catch (Exception error) {
      log.warn("Unable to auto-start DSH: {}", error.getMessage());
    }
  }

  private boolean isRuntimeListening() {
    try {
      URI address = URI.create(properties.getBaseUrl());
      int port = address.getPort() > 0 ? address.getPort() : ("https".equalsIgnoreCase(address.getScheme()) ? 443 : 80);
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(address.getHost(), port), 250);
        return true;
      }
    } catch (Exception ignored) {
      return false;
    }
  }

  private void readOutput(Process current) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher matcher = READY_URL.matcher(line);
        if (matcher.find()) {
          properties.setAuthToken(URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8));
          ready.countDown();
          log.info("DSH is ready at {}", properties.getBaseUrl());
        } else if (log.isDebugEnabled()) {
          log.debug("DSH: {}", line);
        }
      }
    } catch (Exception error) {
      log.debug("DSH output reader stopped: {}", error.getMessage());
    } finally {
      ready.countDown();
      try {
        if (current == process && current.exitValue() != 0) log.warn("DSH exited with code {}", current.exitValue());
      } catch (IllegalThreadStateException ignored) { }
    }
  }

  private String resolveNodeCommand(String configured) {
    if (configured != null && !configured.isBlank() && Files.isExecutable(Path.of(configured))) return configured;
    Path nvmNode = Path.of(System.getProperty("user.home", ""), ".nvm", "versions", "node", "v22.19.0", "bin", "node");
    return Files.isExecutable(nvmNode) ? nvmNode.toString() : (configured == null || configured.isBlank() ? "node" : configured);
  }

  @PreDestroy
  public void stop() {
    Process current = process;
    if (current != null && current.isAlive()) {
      current.destroy();
      try {
        if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        current.destroyForcibly();
      }
    }
  }
}
