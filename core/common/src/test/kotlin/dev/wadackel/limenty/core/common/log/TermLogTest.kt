package dev.wadackel.limenty.core.common.log

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class TermLogTest {
    private lateinit var sink: RingBufferLogSink

    @Before
    fun setUp() {
        TermLog.clearSinks()
        TermLog.minLevel = LogLevel.VERBOSE
        TermLog.clock = { 1_000L }
        sink = RingBufferLogSink(32)
        TermLog.addSink(sink)
    }

    @After
    fun tearDown() {
        TermLog.clearSinks()
        TermLog.minLevel = LogLevel.INFO
        TermLog.clock = System::currentTimeMillis
    }

    @Test
    fun `records carry tag level event and fields`() {
        TermLog.logger(LogTags.SSH).i("connect.start", "host" to "macbook", "port" to 22)

        val record = sink.snapshot().single()
        assertThat(record.tag).isEqualTo(LogTags.SSH)
        assertThat(record.level).isEqualTo(LogLevel.INFO)
        assertThat(record.event).isEqualTo("connect.start")
        assertThat(record.fields).containsExactly("host" to "macbook", "port" to "22").inOrder()
        assertThat(record.timestampMillis).isEqualTo(1_000L)
    }

    @Test
    fun `records below the minimum level are dropped`() {
        TermLog.minLevel = LogLevel.WARN
        val logger = TermLog.logger(LogTags.IME)
        logger.d("composing.update")
        logger.w("composing.lost")

        assertThat(sink.snapshot().map { it.event }).containsExactly("composing.lost")
    }

    @Test
    fun `a throwing sink does not break the others`() {
        TermLog.addSink(LogSink { error("sink failure") })
        val late = RingBufferLogSink(4)
        TermLog.addSink(late)

        TermLog.logger(LogTags.SESSION).i("state.change")

        assertThat(sink.snapshot()).hasSize(1)
        assertThat(late.snapshot()).hasSize(1)
    }

    @Test
    fun `secrets are redacted at the call site`() {
        TermLog.logger(LogTags.SSH).i("auth.start", "user" to "wadackel", "password" to "s3cret".redacted())

        val message = LogFormatter.formatMessage(sink.snapshot().single())
        assertThat(message).doesNotContain("s3cret")
        assertThat(message).isEqualTo("auth.start user=wadackel password=<redacted:6>")
    }

    @Test
    fun `records carry a monotonic sequence so same-millisecond ordering survives`() {
        val logger = TermLog.logger(LogTags.IME)
        repeat(5) { logger.i("composing.update") }

        val sequences = sink.snapshot().map { it.sequence }
        assertThat(sequences).isInStrictOrder()
    }

    @Test
    fun `records carry the producing thread name`() {
        Thread({ TermLog.logger(LogTags.RENDER).i("frame") }, "render-thread").apply {
            start()
            join()
        }

        assertThat(sink.snapshot().single().thread).isEqualTo("render-thread")
    }

    @Test
    fun `a sink only receives records at or above its own level`() {
        TermLog.clearSinks()
        val verbose = RingBufferLogSink(8)
        val info = RingBufferLogSink(8)
        TermLog.addSink(verbose, minLevel = LogLevel.VERBOSE)
        TermLog.addSink(info, minLevel = LogLevel.INFO)

        val logger = TermLog.logger(LogTags.KEY)
        logger.v("key.trace")
        logger.i("key.down")

        assertThat(verbose.snapshot().map { it.event }).containsExactly("key.trace", "key.down").inOrder()
        assertThat(info.snapshot().map { it.event }).containsExactly("key.down")
    }

    @Test
    fun `the global floor still suppresses records below it`() {
        TermLog.clearSinks()
        TermLog.minLevel = LogLevel.DEBUG
        val sink = RingBufferLogSink(8)
        TermLog.addSink(sink, minLevel = LogLevel.VERBOSE)

        TermLog.logger(LogTags.KEY).v("too.detailed")
        TermLog.logger(LogTags.KEY).d("kept")

        assertThat(sink.snapshot().map { it.event }).containsExactly("kept")
    }

    @Test
    fun `isLoggable is false when no sink is attached`() {
        TermLog.clearSinks()
        assertThat(TermLog.isLoggable(LogLevel.ERROR)).isFalse()
    }
}
