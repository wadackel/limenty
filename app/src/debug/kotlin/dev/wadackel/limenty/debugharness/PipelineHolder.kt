package dev.wadackel.limenty.debugharness

import dev.wadackel.limenty.core.input.TerminalInputPipeline

/**
 * The harness encodes events itself instead of letting the pipeline write, so a byte can be
 * attributed to the composition state it was produced under before it reaches the backend.
 */
object PipelineHolder {
    val pipeline = TerminalInputPipeline(sink = {})
}
