package dev.wadackel.limenty.docs

/**
 * What the documentation screenshots show inside the terminal, written as the escape sequences a
 * remote would send.
 *
 * Built for the size the screen actually lays out rather than recorded from a real session: a
 * recording only fits the grid it was made on, and a change to the font or the key bar would
 * then leave the docs showing a wrapped, broken screen until someone re-recorded it by hand.
 */
internal object DocsScenes {
    private const val ESC = "\u001b"

    private fun rgb(hex: Long) = "${(hex shr 16) and 0xff};${(hex shr 8) and 0xff};${hex and 0xff}"
    private fun fg(hex: Long) = "$ESC[38;2;${rgb(hex)}m"
    private fun bg(hex: Long) = "$ESC[48;2;${rgb(hex)}m"
    private const val RESET = "$ESC[0m"
    private fun at(row: Int, column: Int = 0) = "$ESC[${row + 1};${column + 1}H"

    private const val INK = 0xc0caf5L
    private const val DIM = 0x565f89L
    private const val KEYWORD = 0xbb9af7L
    private const val STRING = 0x9ece6aL
    private const val NUMBER = 0xff9e64L
    private const val FUNCTION = 0x7aa2f7L
    private const val TYPE = 0x2ac3deL
    private const val GUTTER = 0x3b4261L
    private const val CURSOR_LINE = 0x292e42L
    private const val ACCENT = 0x8b5cf6L
    private const val BAR = 0x1e1b2eL
    private const val PROMPT = 0xa78bfaL
    private const val PATH = 0x7dcfffL

    private val goSource = """
        package main

        import (
        	"context"
        	"log/slog"
        	"os"
        	"os/signal"
        	"time"

        	"orbit/internal/queue"
        )

        // main starts the worker pool and drains it on SIGINT.
        func main() {
        	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
        	defer stop()

        	q := queue.New(queue.Options{Workers: 8, Retry: 3 * time.Second})
        	go q.Run(ctx)

        	slog.Info("orbit is up", "workers", 8, "pid", os.Getpid())
        	<-ctx.Done()

        	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
        	defer cancel()
        	if err := q.Drain(shutdown); err != nil {
        		slog.Error("drain failed", "err", err)
        		os.Exit(1)
        	}
        }
    """.trimIndent().replace("\t", "    ").lines()

    private val keywords = setOf("package", "import", "func", "defer", "go", "if", "return", "nil", "var", "const")
    private val token = Regex("""//.*|"[^"]*"|\b\d+\b|\b[A-Za-z_]\w*\b|.""")

    /** One line of Go, coloured the way a typical Neovim colour scheme would. */
    private fun highlight(line: String, background: String): String = buildString {
        token.findAll(line).forEach { match ->
            val text = match.value
            val colour = when {
                text.startsWith("//") -> DIM
                text.startsWith("\"") -> STRING
                text.first().isDigit() -> NUMBER
                text in keywords -> KEYWORD
                text.first().isUpperCase() && line.getOrNull(match.range.last + 1) == '(' -> FUNCTION
                text.first().isUpperCase() -> TYPE
                else -> INK
            }
            append(fg(colour)).append(background).append(text)
        }
    }

    private fun neovim(height: Int, columns: Int, cursorLine: Int): String = buildString {
        val gutter = 4
        for (row in 0 until height - 1) {
            val number = row + 1
            val current = number == cursorLine
            val background = if (current) bg(CURSOR_LINE) else ""
            append(at(row))
            if (number <= goSource.size) {
                val source = goSource[number - 1].take(columns - gutter)
                append(fg(if (current) NUMBER else GUTTER)).append(background)
                append(number.toString().padStart(gutter - 1)).append(' ')
                append(highlight(source, background))
                if (current) append(bg(CURSOR_LINE)).append(" ".repeat(columns - gutter - source.length))
            } else {
                append(fg(GUTTER)).append("~")
            }
            append(RESET)
        }
        val mode = " NORMAL "
        val file = " main.go "
        val position = " go  utf-8  $cursorLine:5  Top "
        append(at(height - 1))
        append(bg(FUNCTION)).append(fg(0x1a1b26)).append("$ESC[1m").append(mode).append(RESET)
        append(bg(0x2f334d)).append(fg(INK)).append(file)
        append(bg(BAR)).append(" ".repeat((columns - mode.length - file.length - position.length).coerceAtLeast(0)))
        append(bg(0x2f334d)).append(fg(INK)).append(position).append(RESET)
    }

    private fun meter(label: String, fraction: Double, text: String, width: Int): String {
        val inner = width - label.length - 2
        val filled = (inner * fraction).toInt().coerceIn(0, inner)
        val gap = " ".repeat((inner - filled - text.length).coerceAtLeast(0))
        return "$ESC[36m$label$RESET[$ESC[32m${"|".repeat(filled)}$RESET$gap$ESC[90m$text$RESET]"
    }

    private fun htop(top: Int, height: Int, columns: Int): String = buildString {
        val half = columns / 2 - 1
        val bold = "$ESC[1m"
        append(at(top)).append(meter("  0", 0.42, "41.8%", half))
        append(at(top, half + 2)).append("$ESC[36mTasks: $bold" + "38$RESET$ESC[36m, $bold" + "2$RESET$ESC[36m running$RESET")
        append(at(top + 1)).append(meter("  1", 0.27, "26.9%", half))
        append(at(top + 1, half + 2)).append("$ESC[36mLoad average: $bold" + "0.84 $RESET$ESC[36m0.61 0.47$RESET")
        append(at(top + 2)).append(meter("Mem", 0.58, "2.21G/3.81G", half))
        append(at(top + 2, half + 2)).append("$ESC[36mUptime: $bold" + "4 days, 02:13:51$RESET")
        val header = "  PID USER     CPU% MEM%   TIME+  Command"
        append(at(top + 4)).append("$ESC[30;42m").append(header.padEnd(columns).take(columns)).append(RESET)
        val processes = listOf(
            "  812 ada      38.2  4.7  2:41.07 orbit --workers 8",
            "  790 ada       3.1  0.3  0:12.55 tmux new -s orbit",
            "  833 ada       1.4  0.9  0:08.19 nvim main.go",
            "  421 postgres  0.9  1.5  1:02.43 postgres: checkpointer",
            "  402 root      0.0  0.2  0:00.31 sshd: ada [priv]",
            "  377 ada       0.0  0.4  0:03.88 node dist/worker.js",
            "  356 redis     0.0  0.2  0:21.40 redis-server *:6379",
            "  318 root      0.0  0.1  0:00.92 tailscaled",
            "  901 ada       0.0  0.1  0:00.02 htop",
        )
        processes.take((height - 5).coerceAtLeast(0)).forEachIndexed { index, line ->
            append(at(top + 5 + index))
            if (index == 0) append("$ESC[30;46m").append(line.padEnd(columns).take(columns)).append(RESET) else append(line.take(columns))
        }
    }

    private fun tmuxStatus(row: Int, columns: Int, session: String, windows: String): String = buildString {
        val clock = " 14:02 "
        append(at(row))
        append(bg(ACCENT)).append(fg(0x0f0d17)).append("$ESC[1m $session $RESET")
        append(bg(BAR)).append(fg(0xf5f3ff)).append(" $windows")
        append(" ".repeat((columns - session.length - 3 - windows.length - clock.length).coerceAtLeast(0)))
        append(fg(0xa1a1aa)).append(clock).append(RESET)
    }

    /** Neovim over htop in a tmux session: the screen a phone terminal is opened for. */
    fun tmuxWorkspace(columns: Int, rows: Int): String = buildString {
        val content = rows - 1
        val editor = (content * 0.62).toInt()
        append("$ESC[2J$ESC[?25l")
        append(neovim(height = editor, columns = columns, cursorLine = 18))
        append(at(editor)).append(fg(ACCENT)).append("─".repeat(columns / 2))
        append(fg(GUTTER)).append("─".repeat(columns - columns / 2)).append(RESET)
        append(htop(top = editor + 1, height = content - editor - 1, columns = columns))
        append(tmuxStatus(rows - 1, columns, "orbit", "0:nvim*  1:logs-"))
    }

    private fun prompt() = "${fg(PROMPT)}ada@devbox$RESET ${fg(PATH)}~/src/orbit$RESET ${fg(DIM)}feat/drain$RESET\r\n${fg(PROMPT)}❯$RESET "

    /** A prompt waiting on a commit message, for a preedit to be drawn into. */
    fun commitPrompt(): String = buildString {
        append("$ESC[2J$ESC[H")
        append(prompt()).append("git status --short\r\n")
        append("${fg(STRING)}M$RESET  internal/queue/queue.go\r\n")
        append("${fg(STRING)}M$RESET  main.go\r\n")
        append(prompt()).append("git commit -m \"")
    }
}
