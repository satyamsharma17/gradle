/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.performance.results

import com.google.common.base.Strings
import groovy.transform.CompileStatic
import org.gradle.performance.fixture.BuildExperimentSpec
import org.gradle.performance.fixture.GradleBuildExperimentSpec
import org.gradle.performance.fixture.OutputDirSelector

@CompileStatic
class DefaultOutputDirSelector implements OutputDirSelector {
    private static final String DEBUG_ARTIFACTS_DIRECTORY_PROPERTY_NAME = "org.gradle.performance.debugArtifactsDirectory"

    private final File baseOutputDir

    DefaultOutputDirSelector(File fallbackDirectory) {
        String debugArtifactsDirectoryPath = System.getProperty(DEBUG_ARTIFACTS_DIRECTORY_PROPERTY_NAME)
        this.baseOutputDir = Strings.isNullOrEmpty(debugArtifactsDirectoryPath)
            ? fallbackDirectory
            : new File(debugArtifactsDirectoryPath)
    }

    @Override
    File outputDirFor(String testId, BuildExperimentSpec spec) {
        boolean multiVersion = spec instanceof GradleBuildExperimentSpec && spec.multiVersion
        def outputDir
        if (multiVersion) {
            String version = ((GradleBuildExperimentSpec) spec).getInvocation().gradleDistribution.version.version
            outputDir = new File(outputDirFor(testId), version)
        } else {
            outputDir = new File(outputDirFor(testId), fileSafeNameFor(spec.getDisplayName()))
        }
        outputDir.mkdirs()
        return outputDir
    }

    @Override
    File outputDirFor(String testId) {
        String fileSafeName = fileSafeNameFor(testId)
        return new File(baseOutputDir, shortenPath(fileSafeName, 40))
    }

    private static String fileSafeNameFor(String title) {
        def fileSafeName = title.replaceAll('[^a-zA-Z0-9.-]', '-').replaceAll('-+', '-')
        if (fileSafeName.endsWith('-')) {
            fileSafeName = fileSafeName.substring(0, fileSafeName.length() - 1)
        }
        fileSafeName
    }

    private static String shortenPath(String longName, int expectedMaxLength) {
        if (longName.length() <= expectedMaxLength) {
            return longName
        } else {
            return longName.substring(0, expectedMaxLength - 10) + "." + longName.substring(longName.length() - 9)
        }
    }
}
