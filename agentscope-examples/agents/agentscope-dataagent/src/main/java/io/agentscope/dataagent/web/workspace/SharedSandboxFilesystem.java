/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractFilesystem} that delegates to a fixed {@link Sandbox} reference owned by
 * {@link UserSandboxRegistry}.
 *
 * <p>Used by browser-side controllers (workspace tree, file read/write, upload) so the UI sees
 * exactly the same files the agent does. Unlike {@link
 * io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem} — which is a stable
 * proxy whose sandbox is flipped per-call by {@code
 * io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware} — this filesystem holds
 * the sandbox directly, because controllers are not inside an agent-call lifecycle and have
 * no middleware to do the flip for them.
 *
 * <p><b>Session isolation:</b> When {@link RuntimeContext#getSessionId()} is non-null, all file
 * operations are scoped to {@code /workspace/sessions/<sessionId>/}. This prevents cross-session
 * data leakage when multiple conversations share the same Docker container (keyed by
 * userId+agentId in {@link UserSandboxRegistry}). The rewriting is idempotent — if a path is
 * already under the session directory (e.g., the agent reuses a path seen in command output),
 * it is not rewritten again. When no sessionId is present (browser controllers), operations
 * use the default {@code /workspace} root.
 *
 * <p>The exec/upload/download mapping mirrors {@code SandboxBackedFilesystem} exactly; we don't
 * subclass it because that class implements {@link io.agentscope.harness.agent.sandbox.SandboxAware}
 * and relies on a mutable {@code sandbox} field, which is the wrong contract here (a sandbox
 * supplied to the registry must not be silently overwritten).
 */
public final class SharedSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(SharedSandboxFilesystem.class);

    private static final String WORKSPACE_ROOT = "/workspace";
    private static final String WORKSPACE_PREFIX = "/workspace/";
    private static final String SESSIONS_DIR = "sessions";

    private final String fsId;
    private final Sandbox sandbox;

    public SharedSandboxFilesystem(Sandbox sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.fsId = "shared-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public String id() {
        return fsId;
    }

    // ── Session-scoped path rewriting ────────────────────────────────────────

    /**
     * Returns the session working directory {@code /workspace/sessions/<sessionId>} when the
     * context carries a sessionId, or {@code null} when operations should use the shared
     * {@code /workspace} root (browser controllers without session context).
     */
    private String resolveSessionDir(RuntimeContext ctx) {
        if (ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
            return null;
        }
        return WORKSPACE_ROOT + "/" + SESSIONS_DIR + "/" + ctx.getSessionId();
    }

    /**
     * Rewrites a single file path from the shared {@code /workspace} root to the session
     * directory. Paths already under the session directory are returned unchanged (idempotent).
     */
    private String rewritePath(String path, String sessionDir) {
        if (sessionDir == null || path == null) return path;
        if (path.startsWith(sessionDir + "/") || path.equals(sessionDir)) return path;
        if (path.startsWith(WORKSPACE_PREFIX)) {
            return sessionDir + "/" + path.substring(WORKSPACE_PREFIX.length());
        }
        if (path.equals(WORKSPACE_ROOT)) return sessionDir;
        return path;
    }

    /**
     * Rewrites {@code /workspace} paths in a shell command to the session directory.
     *
     * <p>The rewriting is idempotent: if all {@code /workspace/} occurrences are already
     * session-scoped, the command is returned unchanged. This handles the case where the agent
     * reuses a path it saw in previous command output (which already contains the session prefix).
     */
    private String rewriteCommand(String command, String sessionDir) {
        if (sessionDir == null || command == null) return command;
        if (!command.contains(WORKSPACE_PREFIX) && !command.contains(WORKSPACE_ROOT)) {
            return command;
        }
        if (command.contains(sessionDir + "/")
                || command.contains(sessionDir + "'")
                || command.contains(sessionDir + " ")
                || command.endsWith(sessionDir)) {
            return command;
        }
        String result = command.replace(WORKSPACE_PREFIX, sessionDir + "/");
        result = result.replace(WORKSPACE_ROOT, sessionDir);
        return result;
    }

    /** Ensures the session directory exists before any operation uses it. */
    private void ensureSessionDir(RuntimeContext ctx, String sessionDir) {
        try {
            sandbox.exec(ctx, "mkdir -p " + shellSingleQuote(sessionDir), null);
        } catch (Exception e) {
            log.warn("[shared-sandbox-fs] failed to create session dir: {}", sessionDir, e);
        }
    }

    /** Prepends {@code mkdir -p <sessionDir> &&} to a command when session-scoped. */
    private String scopeCommand(String command, String sessionDir) {
        if (sessionDir == null) return command;
        return "mkdir -p " + shellSingleQuote(sessionDir) + " && " + command;
    }

    private static String shellSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ── Overridden operations ────────────────────────────────────────────────

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        String sessionDir = resolveSessionDir(runtimeContext);
        String effectiveCmd = rewriteCommand(command, sessionDir);
        if (sessionDir != null) {
            effectiveCmd = scopeCommand(effectiveCmd, sessionDir);
        }
        try {
            ExecResult result = sandbox.exec(runtimeContext, effectiveCmd, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[shared-sandbox-fs] execute failed: {}", effectiveCmd, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    /**
     * Overrides {@code edit} to rewrite the file path before the parent class base64-encodes it
     * into the Python payload. Without this, the encoded path would not contain the literal
     * {@code /workspace} string, so the {@link #execute()} rewrite would miss it.
     */
    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        String sessionDir = resolveSessionDir(runtimeContext);
        if (sessionDir != null) {
            ensureSessionDir(runtimeContext, sessionDir);
            filePath = rewritePath(filePath, sessionDir);
        }
        return super.edit(runtimeContext, filePath, oldString, newString, replaceAll);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        String sessionDir = resolveSessionDir(runtimeContext);
        if (sessionDir != null) {
            ensureSessionDir(runtimeContext, sessionDir);
        }
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            String originalPath = file.getKey();
            String effectivePath = rewritePath(originalPath, sessionDir);
            byte[] content = file.getValue();
            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = shellSingleQuote(effectivePath);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && printf '%s' '"
                                + base64Content
                                + "' | base64 -d > "
                                + escapedPath;
                if (sessionDir != null) {
                    cmd = scopeCommand(cmd, sessionDir);
                }
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    results.add(FileUploadResponse.success(originalPath));
                } else {
                    results.add(FileUploadResponse.fail(originalPath, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(originalPath, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] uploadFiles failed for path: {}", originalPath, e);
                results.add(FileUploadResponse.fail(originalPath, e.getMessage()));
            }
        }
        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        String sessionDir = resolveSessionDir(runtimeContext);
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String originalPath : paths) {
            String effectivePath = rewritePath(originalPath, sessionDir);
            try {
                // Use -w 0 to prevent line wrapping (GNU coreutils & BusyBox)
                String cmd = "base64 -w 0 " + shellSingleQuote(effectivePath);
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    // Strip all whitespace chars before base64 decoding
                    String b64 = r.stdout().replaceAll("\\s", "");
                    byte[] decoded =
                            Base64.getDecoder().decode(b64.getBytes(StandardCharsets.UTF_8));
                    results.add(FileDownloadResponse.success(originalPath, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(originalPath, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(originalPath, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] downloadFiles failed for path: {}", originalPath, e);
                results.add(FileDownloadResponse.fail(originalPath, e.getMessage()));
            }
        }
        return results;
    }
}
