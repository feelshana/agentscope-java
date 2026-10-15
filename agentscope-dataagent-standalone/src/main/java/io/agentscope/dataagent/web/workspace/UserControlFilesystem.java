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
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;

/** User-scoped platform records. Route paths beginning with '/' are virtual, not host paths. */
public final class UserControlFilesystem extends LocalFilesystem {
    public UserControlFilesystem(Path root) {
        super(root, true, 10, IsolationScope.USER.toNamespaceFactory());
    }

    @Override
    protected Path resolvePath(RuntimeContext context, String key) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank())
            throw new SecurityException("平台会话记录缺少用户身份");
        String relative = key == null ? "" : key;
        while (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.contains("\\") || relative.matches("^[A-Za-z]:.*"))
            throw new SecurityException("平台会话记录路径无效");
        return super.resolvePath(context, relative.isEmpty() ? "." : relative);
    }
}
