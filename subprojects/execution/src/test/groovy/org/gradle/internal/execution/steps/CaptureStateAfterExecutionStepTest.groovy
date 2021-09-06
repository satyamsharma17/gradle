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

package org.gradle.internal.execution.steps

import com.google.common.collect.ImmutableSortedMap
import org.gradle.internal.execution.OutputSnapshotter
import org.gradle.internal.execution.history.BeforeExecutionState
import org.gradle.internal.execution.history.OverlappingOutputs
import org.gradle.internal.execution.history.PreviousExecutionState
import org.gradle.internal.file.FileMetadata
import org.gradle.internal.file.impl.DefaultFileMetadata
import org.gradle.internal.hash.HashCode
import org.gradle.internal.id.UniqueId
import org.gradle.internal.snapshot.FileSystemLocationSnapshot
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.MerkleDirectorySnapshotBuilder
import org.gradle.internal.snapshot.RegularFileSnapshot

import java.time.Duration

import static org.gradle.internal.snapshot.MerkleDirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy.INCLUDE_EMPTY_DIRS

class CaptureStateAfterExecutionStepTest extends StepSpec<BeforeExecutionContext> {

    def buildInvocationScopeId = UniqueId.generate()
    def outputSnapshotter = Mock(OutputSnapshotter)
    def delegateResult = Mock(Result)

    def step = new CaptureStateAfterExecutionStep(buildOperationExecutor, buildInvocationScopeId, outputSnapshotter, delegate)

    @Override
    protected ValidationFinishedContext createContext() {
        Stub(ValidationFinishedContext) {
            getInputProperties() >> ImmutableSortedMap.of()
            getInputFileProperties() >> ImmutableSortedMap.of()
        }
    }

    def "no state is captured if before execution state is unavailable"() {
        def delegateDuration = Duration.ofMillis(123)

        when:
        def result = step.execute(work, context)
        then:
        !result.afterExecutionState.present
        !result.reused
        result.duration == delegateDuration
        result.originMetadata.buildInvocationId == buildInvocationScopeId.asString()
        result.originMetadata.executionTime >= result.duration

        1 * delegate.execute(work, context) >> delegateResult
        1 * delegateResult.duration >> delegateDuration
        _ * context.beforeExecutionState >> Optional.empty()
        assertNoOperation()
        0 * _
    }

    def "no state is captured if snapshotting outputs fail"() {
        def delegateDuration = Duration.ofMillis(123)
        def failure = new OutputSnapshotter.OutputFileSnapshottingException("output", new IOException("Error")) {}

        when:
        def result = step.execute(work, context)
        then:
        !result.afterExecutionState.present
        !result.reused
        result.duration == delegateDuration
        result.originMetadata.buildInvocationId == buildInvocationScopeId.asString()
        result.originMetadata.executionTime >= result.duration

        1 * delegate.execute(work, context) >> delegateResult
        1 * delegateResult.duration >> delegateDuration
        _ * context.beforeExecutionState >> Optional.of(Mock(BeforeExecutionState) {
            _ * detectedOverlappingOutputs >> Optional.empty()
        })
        1 * outputSnapshotter.snapshotOutputs(work, _) >> { throw failure }
        assertOperation()
        0 * _
    }

    def "non-overlapping outputs are captured"() {
        def delegateDuration = Duration.ofMillis(123)
        def outputSnapshots = ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", Mock(FileSystemSnapshot))

        when:
        def result = step.execute(work, context)
        then:
        result.afterExecutionState.get().outputFilesProducedByWork == outputSnapshots
        !result.reused
        result.duration == delegateDuration
        result.originMetadata.buildInvocationId == buildInvocationScopeId.asString()
        result.originMetadata.executionTime >= result.duration

        1 * delegate.execute(work, context) >> delegateResult
        1 * delegateResult.duration >> delegateDuration
        _ * context.beforeExecutionState >> Optional.of(Mock(BeforeExecutionState) {
            _ * detectedOverlappingOutputs >> Optional.empty()
        })
        1 * outputSnapshotter.snapshotOutputs(work, _) >> outputSnapshots
        assertOperation()
        0 * _
    }

    def "overlapping outputs are captured"() {
        def delegateDuration = Duration.ofMillis(123)

        def staleFile = fileSnapshot("stale", HashCode.fromInt(123))
        def outputFile = fileSnapshot("outputs", HashCode.fromInt(345))

        def emptyDirectory = directorySnapshot()
        def directoryWithStaleFile = directorySnapshot(staleFile)
        def directoryWithStaleFileAndOutput = directorySnapshot(outputFile, staleFile)
        def directoryWithOutputFile = directorySnapshot(outputFile)

        def previousOutputs = ImmutableSortedMap.<String, FileSystemSnapshot> of(
            "outputDir", emptyDirectory
        )
        def outputsBeforeExecution = ImmutableSortedMap.<String, FileSystemSnapshot> of(
            "outputDir", directoryWithStaleFile
        )
        def outputsAfterExecution = ImmutableSortedMap.<String, FileSystemSnapshot> of(
            "outputDir", directoryWithStaleFileAndOutput
        )
        def filteredOutputs = ImmutableSortedMap.<String, FileSystemSnapshot> of(
            "outputDir", directoryWithOutputFile
        )
        def overlappingOutputs = Mock(OverlappingOutputs)

        when:
        def result = step.execute(work, context)
        then:
        result.afterExecutionState.get().outputFilesProducedByWork == filteredOutputs
        !result.reused
        result.duration == delegateDuration
        result.originMetadata.buildInvocationId == buildInvocationScopeId.asString()
        result.originMetadata.executionTime >= result.duration

        1 * delegate.execute(work, context) >> delegateResult
        1 * delegateResult.duration >> delegateDuration
        _ * context.beforeExecutionState >> Optional.of(Mock(BeforeExecutionState) {
            _ * detectedOverlappingOutputs >> Optional.of(overlappingOutputs)
            _ * outputFileLocationSnapshots >> outputsBeforeExecution
        })
        _ * context.previousExecutionState >> Optional.of(Mock(PreviousExecutionState) {
            _ * outputFilesProducedByWork >> previousOutputs
        })
        1 * outputSnapshotter.snapshotOutputs(work, _) >> outputsAfterExecution
        assertOperation()
        0 * _
    }

    private void assertOperation(Throwable expectedFailure = null) {
        if (expectedFailure == null) {
            assertSuccessfulOperation(CaptureStateAfterExecutionStep.Operation, "Snapshot outputs after executing job ':test'", CaptureStateAfterExecutionStep.Operation.Result.INSTANCE)
        } else {
            assertFailedOperation(CaptureStateAfterExecutionStep.Operation, "Snapshot outputs after executing job ':test'", expectedFailure)
        }
    }

    private static RegularFileSnapshot fileSnapshot(String name, HashCode contentHash) {
        new RegularFileSnapshot("/absolute/${name}", name, contentHash, DefaultFileMetadata.file(0L, 0L, FileMetadata.AccessType.DIRECT))
    }

    private static FileSystemLocationSnapshot directorySnapshot(RegularFileSnapshot... contents) {
        def builder = MerkleDirectorySnapshotBuilder.sortingRequired()
        builder.enterDirectory(FileMetadata.AccessType.DIRECT, "/absolute", "absolute", INCLUDE_EMPTY_DIRS)
        contents.each {
            builder.visitLeafElement(it)
        }
        builder.leaveDirectory()
        return builder.result
    }
}
