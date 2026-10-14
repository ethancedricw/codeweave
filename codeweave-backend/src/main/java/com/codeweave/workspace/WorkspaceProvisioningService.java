package com.codeweave.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Creates project workspaces on the server so every authorized client uses the same files. */
@Service
public class WorkspaceProvisioningService {
  private final Path root;
  private final Duration commandTimeout = Duration.ofMinutes(10);

  public WorkspaceProvisioningService(@Value("${codeweave.workspace.root:${java.io.tmpdir}/codeweave-workspaces}") String root) {
    this.root = Paths.get(root).toAbsolutePath().normalize();
  }

  public Map<String, Object> provision(String projectId, String type, String repoUrl, String branch) {
    return provision(projectId, type, repoUrl, branch, "");
  }

  public Map<String, Object> provision(String projectId, String type, String repoUrl, String branch, String sourcePath) {
    return provision(projectId, type, repoUrl, branch, sourcePath, "", "");
  }

  public Map<String, Object> provision(String projectId, String type, String repoUrl, String branch, String sourcePath, String gitUsername, String gitToken) {
    Path target = pathFor(projectId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("serverPath", target.toString());
    result.put("syncAt", Instant.now().toString());
    try {
      Files.createDirectories(root);
      if ("git".equals(type)) {
        if (repoUrl == null || repoUrl.isBlank()) return failure(result, "Git 仓库地址不能为空");
        if (Files.exists(target.resolve(".git"))) {
          runGit(target, gitUsername, gitToken, "git", "fetch", "--all", "--prune");
          runGit(target, gitUsername, gitToken, "git", "checkout", branch == null || branch.isBlank() ? "main" : branch);
          runGit(target, gitUsername, gitToken, "git", "pull", "--ff-only");
        } else {
          deleteDirectory(target);
          Files.createDirectories(target.getParent());
          String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;
          try {
            runGit(root, gitUsername, gitToken, "git", "clone", "--depth", "1", "--branch", selectedBranch, repoUrl, target.toString());
          } catch (IOException branchError) {
            if (!"main".equals(selectedBranch)) throw branchError;
            deleteDirectory(target);
            runGit(root, gitUsername, gitToken, "git", "clone", "--depth", "1", repoUrl, target.toString());
          }
        }
        result.put("status", "ready");
      } else if ("snapshot".equals(type)) {
        if (sourcePath != null && !sourcePath.isBlank() && Files.isDirectory(Paths.get(sourcePath))) {
          copyLocalDirectory(sourcePath, target);
          result.put("status", "ready");
        } else {
          Files.createDirectories(target);
          // A legacy project without a source still needs a usable shared workspace.
          // A later snapshot upload can populate this directory without changing its identity.
          result.put("status", "ready");
        }
      } else {
        if (sourcePath != null && !sourcePath.isBlank()) copyLocalDirectory(sourcePath, target);
        else Files.createDirectories(target);
        result.put("status", "ready");
      }
    } catch (Exception error) {
      return failure(result, error.getMessage() == null ? "服务器工作区准备失败" : error.getMessage());
    }
    return result;
  }

  public void testGit(String repoUrl, String branch, String gitUsername, String gitToken) {
    if (repoUrl == null || repoUrl.isBlank()) throw new IllegalArgumentException("Git 仓库地址不能为空");
    try {
      URI uri = URI.create(repoUrl.trim());
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()) || "ssh".equalsIgnoreCase(uri.getScheme()) || "git".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException("Git 地址格式不支持");
      Files.createDirectories(root);
      String ref = branch == null || branch.isBlank() ? "HEAD" : "refs/heads/" + branch.trim();
      String output = runGit(root, gitUsername, gitToken, "git", "ls-remote", repoUrl.trim(), ref);
      if (output.isBlank()) throw new IllegalArgumentException("仓库可连接，但找不到指定分支");
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException(error.getMessage() == null || error.getMessage().isBlank() ? "Git 仓库连接失败" : error.getMessage());
    }
  }

  private void copyLocalDirectory(String sourcePath, Path target) throws IOException {
    Path source = Paths.get(sourcePath).toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) throw new IOException("本地项目目录在服务器上不存在");
    deleteDirectory(target);
    try (var stream = Files.walk(source)) {
      for (Path item : stream.toList()) {
        Path relative = source.relativize(item);
        Path destination = target.resolve(relative).normalize();
        if (!destination.startsWith(target)) throw new IOException("本地项目包含非法路径");
        if (Files.isDirectory(item)) Files.createDirectories(destination);
        else {
          Files.createDirectories(destination.getParent());
          Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  public Map<String, Object> materializeSnapshot(String projectId, MultipartFile file) {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
    Path target = pathFor(projectId);
    try {
      deleteDirectory(target);
      Files.createDirectories(target);
      String name = file.getOriginalFilename() == null ? "snapshot.zip" : file.getOriginalFilename().toLowerCase();
      if (!name.endsWith(".zip")) throw new IllegalArgumentException("服务器工作区目前支持 ZIP 快照");
      unzipSafely(file, target);
      return Map.of("serverPath", target.toString(), "status", "ready", "syncAt", Instant.now().toString());
    } catch (IOException error) {
      throw new IllegalStateException("快照解压失败", error);
    }
  }

  private Map<String, Object> failure(Map<String, Object> result, String message) {
    result.put("status", "error");
    result.put("workspaceError", message);
    return result;
  }

  private Path pathFor(String projectId) {
    String safe = projectId == null ? UUID.randomUUID().toString() : projectId.replaceAll("[^a-zA-Z0-9_-]", "_");
    Path path = root.resolve(safe).normalize();
    if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid project workspace");
    return path;
  }

  private String runGit(Path directory, String username, String password, String... command) throws IOException, InterruptedException {
    Path credentialHome = Files.createTempDirectory("codeweave-git-");
    try {
      if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
        String host = "";
        for (String value : command) {
          try { URI uri = URI.create(value); if (uri.getHost() != null) { host = uri.getHost(); break; } } catch (Exception ignored) { }
        }
        if (!host.isBlank()) Files.writeString(credentialHome.resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "_netrc" : ".netrc"), "machine " + host + " login " + username.replaceAll("[\\r\\n]", "") + " password " + password.replaceAll("[\\r\\n]", "") + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      }
      ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
      builder.environment().put("HOME", credentialHome.toString());
      builder.environment().put("USERPROFILE", credentialHome.toString());
      builder.environment().put("GIT_TERMINAL_PROMPT", "0");
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes());
      if (!process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("服务器同步超时");
      }
      if (process.exitValue() != 0) throw new IOException(output.isBlank() ? "Git 仓库操作失败" : output.trim());
      return output;
    } finally {
      deleteDirectory(credentialHome);
    }
  }

  private void unzipSafely(MultipartFile file, Path target) throws IOException {
    try (ZipInputStream input = new ZipInputStream(file.getInputStream())) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = input.getNextEntry()) != null) {
        Path destination = target.resolve(entry.getName()).normalize();
        if (!destination.startsWith(target)) throw new IOException("快照包含非法路径");
        if (entry.isDirectory()) Files.createDirectories(destination);
        else {
          Files.createDirectories(destination.getParent());
          try (var output = Files.newOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
          }
        }
      }
    }
  }

  private void deleteDirectory(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (var stream = Files.walk(path)) {
      stream.sorted(Comparator.reverseOrder()).forEach(item -> {
        try { Files.deleteIfExists(item); } catch (IOException error) { throw new IllegalStateException(error); }
      });
    }
  }
}
