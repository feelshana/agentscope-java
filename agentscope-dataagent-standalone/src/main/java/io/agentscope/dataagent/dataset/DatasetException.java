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
package io.agentscope.dataagent.dataset;

/** Raised for dataset ingestion / lifecycle failures that map to a client- or server-side error. */
public class DatasetException extends RuntimeException {

    private final int status;

    public DatasetException(String message) {
        this(message, 400, null);
    }

    public DatasetException(String message, int status) {
        this(message, status, null);
    }

    public DatasetException(String message, Throwable cause) {
        this(message, 500, cause);
    }

    public DatasetException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
