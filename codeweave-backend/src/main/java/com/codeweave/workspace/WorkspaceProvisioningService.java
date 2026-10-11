package com.codeweave.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
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
    Path target = pathFor(projectId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("serverPath", target.toString());
    result.put("syncAt", Instant.now().toString());
    try {
      Files.createDirectories(root);
      if ("git".equals(type)) {
        if (repoUrl == null || repoUrl.isBlank()) return failure(result, "Git 仓库地址不能为空");
        if (Files.exists(target.resolve(".git"))) {
          run(target, "git", "fetch", "--all", "--prune");
          run(target, "git", "checkout", branch == null || branch.isBlank() ? "main" : branch);
          run(target, "git", "pull", "--ff-only");
        } else {
          deleteDirectory(target);
          Files.createDirectories(target.getParent());
          String selectedBranch = branch == null || branch.isBlank() ? "main" : branch;
          try {
            run(root, "git", "clone", "--depth", "1", "--branch", selectedBranch, repoUrl, target.toString());
          } catch (IOException branchError) {
            if (!"main".equals(selectedBranch)) throw branchError;
            deleteDirectory(target);
            run(root, "git", "clone", "--depth", "1", repoUrl, target.toString());
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

  private void run(Path directory, String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    if (!process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("服务器同步超时");
    }
    if (process.exitValue() != 0) throw new IOException(output.isBlank() ? "服务器同步失败" : output.trim());
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
