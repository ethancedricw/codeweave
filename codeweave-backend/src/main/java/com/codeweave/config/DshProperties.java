package com.codeweave.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "codeweave.agent.dsh")
public class DshProperties {
  private String baseUrl = "http://127.0.0.1:3080";
  private int timeoutSeconds = 15;
  private String authToken = "";
  /** Secret used to authenticate DSH's server-side model proxy calls. */
  private String proxySecret = "";
  private String proxyBaseUrl = "http://127.0.0.1:8080";
  private final String generatedProxySecret = java.util.UUID.randomUUID().toString();
  private String consoleUrl = "";
  private boolean autoStart;
  private String workingDirectory = "";
  private String nodeCommand = "node";
  private List<String> launchArgs = new ArrayList<>();
  private int startupTimeoutSeconds = 15;

  public String getConsoleUrl() { return consoleUrl; }
  public void setConsoleUrl(String consoleUrl) { this.consoleUrl = consoleUrl; }

  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public int getTimeoutSeconds() { return timeoutSeconds; }
  public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
  public String getAuthToken() { return authToken; }
  public void setAuthToken(String authToken) { this.authToken = authToken; }
  public String getProxySecret() { return proxySecret == null || proxySecret.isBlank() ? (authToken == null || authToken.isBlank() ? generatedProxySecret : authToken) : proxySecret; }
  public void setProxySecret(String proxySecret) { this.proxySecret = proxySecret; }
  public String getProxyBaseUrl() { return proxyBaseUrl; }
  public void setProxyBaseUrl(String proxyBaseUrl) { this.proxyBaseUrl = proxyBaseUrl; }
  public boolean isAutoStart() { return autoStart; }
  public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
  public String getWorkingDirectory() { return workingDirectory; }
  public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
  public String getNodeCommand() { return nodeCommand; }
  public void setNodeCommand(String nodeCommand) { this.nodeCommand = nodeCommand; }
  public List<String> getLaunchArgs() { return launchArgs; }
  public void setLaunchArgs(List<String> launchArgs) { this.launchArgs = launchArgs == null ? new ArrayList<>() : new ArrayList<>(launchArgs); }
  public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
  public void setStartupTimeoutSeconds(int startupTimeoutSeconds) { this.startupTimeoutSeconds = startupTimeoutSeconds; }
}
