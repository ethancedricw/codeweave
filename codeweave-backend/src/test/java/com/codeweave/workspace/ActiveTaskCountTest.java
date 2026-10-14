package com.codeweave.workspace;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveTaskCountTest {
  private final Map<String, Object> project = Map.of("id", "p", "serverPath", "/work/p", "dshWorkspaceId", "w");

  @Test
  void countsRunningAndApprovalButExcludesOtherProjectsAndTerminalStates() {
    var sessions = List.<Map<String, Object>>of(
      Map.of("id", "a", "projectId", "p", "state", "运行中"),
      Map.of("id", "b", "projectId", "p", "state", "等待审批"),
      Map.of("id", "c", "projectId", "p", "state", "已完成"),
      Map.of("id", "d", "projectId", "other", "state", "运行中"));
    assertEquals(2, WorkspaceController.activeTaskCount(project, sessions, List.of()));
  }

  @Test
  void nativeStatusOverridesStaleLocalStatusAndDeduplicates() {
    var sessions = List.<Map<String, Object>>of(Map.of("id", "local", "dshSessionId", "native", "projectId", "p", "state", "运行中"));
    var nativeSessions = List.<Map<String, Object>>of(
      Map.of("sessionId", "native", "cwd", "/work/p", "running", false),
      Map.of("sessionId", "new", "workspaceId", "w", "running", true),
      Map.of("sessionId", "child", "cwd", "/work/p/sub", "running", true),
      Map.of("sessionId", "other", "cwd", "/work/p-other", "running", true),
      Map.of("sessionId", "blank", "cwd", "/work/p", "running", true, "blank", true));
    assertEquals(2, WorkspaceController.activeTaskCount(project, sessions, nativeSessions));
  }
}
