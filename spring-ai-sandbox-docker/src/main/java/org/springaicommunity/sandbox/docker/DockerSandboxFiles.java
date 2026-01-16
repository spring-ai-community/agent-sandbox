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

package org.springaicommunity.sandbox.docker;

import java.util.List;
import org.springaicommunity.sandbox.FileSpec;
import org.springaicommunity.sandbox.Sandbox;
import org.springaicommunity.sandbox.SandboxException;
import org.springaicommunity.sandbox.SandboxFiles;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Docker container implementation of {@link SandboxFiles}.
 *
 * <p>
 * Provides file operations for {@link DockerSandbox} using container exec commands. Files
 * are created and read inside the running container.
 * </p>
 *
 * @author Mark Pollack
 * @since 0.1.0
 */
class DockerSandboxFiles implements SandboxFiles {

	private final DockerSandbox sandbox;

	private final GenericContainer<?> container;

	DockerSandboxFiles(DockerSandbox sandbox, GenericContainer<?> container) {
		this.sandbox = sandbox;
		this.container = container;
	}

	@Override
	public SandboxFiles create(String relativePath, String content) {
		try {
			String fullPath = "/work/" + relativePath;

			// Create parent directories
			String parentDir = getParentPath(fullPath);
			if (parentDir != null) {
				ExecResult mkdirResult = container.execInContainer("mkdir", "-p", parentDir);
				if (mkdirResult.getExitCode() != 0) {
					throw new SandboxException("Failed to create parent directory: " + parentDir + " - "
							+ mkdirResult.getStderr() + mkdirResult.getStdout());
				}
			}

			// Write file content using printf to handle special characters and
			// multi-line content
			// We use printf %s to write the exact content without interpretation
			ExecResult writeResult = container.execInContainer("bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash",
					content, fullPath);

			if (writeResult.getExitCode() != 0) {
				throw new SandboxException("Failed to create file: " + relativePath + " - " + writeResult.getStderr());
			}
			return this;
		}
		catch (SandboxException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SandboxException("Failed to create file: " + relativePath, e);
		}
	}

	@Override
	public SandboxFiles createDirectory(String relativePath) {
		try {
			String fullPath = "/work/" + relativePath;
			ExecResult result = container.execInContainer("mkdir", "-p", fullPath);
			if (result.getExitCode() != 0) {
				throw new SandboxException("Failed to create directory: " + relativePath + " - " + result.getStderr());
			}
			return this;
		}
		catch (SandboxException e) {
			throw e;
		}
		catch (Exception e) {
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
			String fullPath = "/work/" + relativePath;
			ExecResult result = container.execInContainer("cat", fullPath);
			if (result.getExitCode() != 0) {
				throw new SandboxException("Failed to read file: " + relativePath + " - " + result.getStderr());
			}
			return result.getStdout();
		}
		catch (SandboxException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SandboxException("Failed to read file: " + relativePath, e);
		}
	}

	@Override
	public boolean exists(String relativePath) {
		try {
			String fullPath = "/work/" + relativePath;
			ExecResult result = container.execInContainer("test", "-e", fullPath);
			return result.getExitCode() == 0;
		}
		catch (Exception e) {
			return false;
		}
	}

	@Override
	public Sandbox and() {
		return sandbox;
	}

	private String getParentPath(String path) {
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash > 0) {
			return path.substring(0, lastSlash);
		}
		return null;
	}

}
