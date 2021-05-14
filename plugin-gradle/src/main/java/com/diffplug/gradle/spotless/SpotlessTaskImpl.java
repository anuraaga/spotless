/*
 * Copyright 2016-2021 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.gradle.spotless;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import com.diffplug.common.base.StringPrinter;
import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.PaddedCell;

@CacheableTask
public abstract class SpotlessTaskImpl extends SpotlessTask {

	private static final List<String> JAVA_16_EXPORTS;

	static {
		List<String> java16Exports = new ArrayList<>();
		java16Exports.add("--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
		java16Exports.add("--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED");
		java16Exports.add("--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED");
		java16Exports.add("--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");
		java16Exports.add("--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
		JAVA_16_EXPORTS = Collections.unmodifiableList(java16Exports);
	}

	@Inject
	public abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void performAction(InputChanges inputs) throws Exception {
		if (target == null) {
			throw new GradleException("You must specify 'Iterable<File> target'");
		}

		if (!inputs.isIncremental()) {
			getLogger().info("Not incremental: removing prior outputs");
			getProject().delete(outputDirectory);
			Files.createDirectories(outputDirectory.toPath());
		}

		final WorkQueue workQueue;
		if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_16)) {
			workQueue = getWorkerExecutor().processIsolation(worker -> worker.getForkOptions().jvmArgs(JAVA_16_EXPORTS));
		} else {
			workQueue = getWorkerExecutor().noIsolation();
		}

		try (Formatter formatter = buildFormatter()) {
			for (FileChange fileChange : inputs.getFileChanges(target)) {
				File input = fileChange.getFile();
				boolean isClean = ratchet != null && ratchet.isClean(getProject(), rootTreeSha, input);
				workQueue.submit(SpotlessWorkAction.class, params -> {
					params.getInput().set(input);
					params.getOutputDirectory().set(outputDirectory);
					params.getProjectDirectory().set(getProject().getProjectDir());
					params.getProjectPath().set(getProject().getPath());
					params.getChangeType().set(fileChange.getChangeType());
					params.getFormatter().set(formatter);
					params.getIsClean().set(isClean);
				});
			}
		}
	}

	interface SpotlessWorkParameters extends WorkParameters {
		Property<File> getInput();

		Property<File> getOutputDirectory();

		Property<File> getProjectDirectory();

		Property<String> getProjectPath();

		Property<ChangeType> getChangeType();

		Property<Formatter> getFormatter();

		Property<Boolean> getIsClean();
	}

	abstract static class SpotlessWorkAction implements WorkAction<SpotlessWorkParameters> {

		private final FileSystemOperations fileSystemOperations;

		@Inject
		public SpotlessWorkAction(FileSystemOperations fileSystemOperations) {
			this.fileSystemOperations = fileSystemOperations;
		}

		@Override
		public void execute() {
			Formatter formatter = getParameters().getFormatter().get();
			File input = getParameters().getInput().get();
			try {
				if (getParameters().getChangeType().get() == ChangeType.REMOVED) {
					deletePreviousResult(input);
				} else {
					if (input.isFile()) {
						processInputFile(formatter, input);
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} finally {
				formatter.close();
			}
		}

		private void deletePreviousResult(File input) throws IOException {
			File output = getOutputFile(input);
			if (output.isDirectory()) {
				final List<File> toDelete;
				try (Stream<Path> s = Files.walk(output.toPath())) {
					toDelete = s.sorted(Comparator.reverseOrder())
							.map(Path::toFile)
							.collect(Collectors.toList());
				}
				fileSystemOperations.delete(spec -> spec.delete(toDelete));
			} else {
				fileSystemOperations.delete(spec -> spec.delete(output));
			}
		}

		private void processInputFile(Formatter formatter, File input) throws IOException {
			File output = getOutputFile(input);
			final PaddedCell.DirtyState dirtyState;
			if (Boolean.TRUE.equals(getParameters().getIsClean().get())) {
				dirtyState = PaddedCell.isClean();
			} else {
				dirtyState = PaddedCell.calculateDirtyState(formatter, input);
			}
			if (dirtyState.isClean()) {
				// Remove previous output if it exists
				fileSystemOperations.delete(spec -> spec.delete(output));
			} else if (dirtyState.didNotConverge()) {
				System.out.println("Skipping '" + input + "' because it does not converge.  Run `spotlessDiagnose` to understand why");
			} else {
				Path parentDir = output.toPath().getParent();
				if (parentDir == null) {
					throw new IllegalStateException("Every file has a parent folder.");
				}
				Files.createDirectories(parentDir);
				// Need to copy the original file to the tmp location just to remember the file attributes
				Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
				dirtyState.writeCanonicalTo(output);
			}
		}

		private File getOutputFile(File input) {
			File projectDir = getParameters().getProjectDirectory().get();
			String outputFileName = FormatExtension.relativize(projectDir, input);
			if (outputFileName == null) {
				throw new IllegalArgumentException(StringPrinter.buildString(printer -> {
					printer.println("Spotless error! All target files must be within the project root. In project " + getParameters().getProjectPath().get());
					printer.println("  root dir: " + projectDir.getAbsolutePath());
					printer.println("    target: " + input.getAbsolutePath());
				}));
			}
			return new File(getParameters().getOutputDirectory().get(), outputFileName);
		}
	}
}
