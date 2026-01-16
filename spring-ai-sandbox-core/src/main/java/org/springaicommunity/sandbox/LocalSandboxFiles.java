/*
 * Copyright 2024 Spring AI Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Local filesystem implementation of {@link SandboxFiles}.
 *
 * <p>
 * Provides file operations for {@link LocalSandbox} using {@link java.nio.file.Files}.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
class LocalSandboxFiles implements SandboxFiles {

	private final LocalSandbox sandbox;

	private final Path workDir;

	LocalSandboxFiles(LocalSandbox sandbox, Path workDir) {
		this.sandbox = sandbox;
		this.workDir = workDir;
	}

	@Override
	public SandboxFiles create(String relativePath, String content) {
		try {
			Path filePath = workDir.resolve(relativePath);
			// Create parent directories if needed
			Path parent = filePath.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.writeString(filePath, content, StandardCharsets.UTF_8);
			return this;
		}
		catch (IOException e) {
			throw new SandboxException("Failed to create file: " + relativePath, e);
		}
	}

	@Override
	public SandboxFiles createDirectory(String relativePath) {
		try {
			Path dirPath = workDir.resolve(relativePath);
			Files.createDirectories(dirPath);
			return this;
		}
		catch (IOException e) {
			throw new SandboxException("Failed to create directory: " + relativePath, e);
		}
	}

	@Override
	public SandboxFiles setup(List<FileSpec> files) {
		for (FileSpec file : files) {
			create(file.path(), file.content());
		}
		return this;
	}

	@Override
	public String read(String relativePath) {
		try {
			Path filePath = workDir.resolve(relativePath);
			return Files.readString(filePath, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new SandboxException("Failed to read file: " + relativePath, e);
		}
	}

	@Override
	public boolean exists(String relativePath) {
		Path path = workDir.resolve(relativePath);
		return Files.exists(path);
	}

	@Override
	public Sandbox and() {
		return sandbox;
	}

}
