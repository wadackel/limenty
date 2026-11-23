package dev.wadackel.limenty.data

import android.content.Context
import dev.wadackel.limenty.core.common.log.LogTags
import dev.wadackel.limenty.core.common.log.TermLog
import dev.wadackel.limenty.core.input.Gesture
import dev.wadackel.limenty.core.input.GestureBehaviour
import dev.wadackel.limenty.core.input.GestureBindings
import dev.wadackel.limenty.core.input.KeyStep
import dev.wadackel.limenty.core.terminal.TerminalPalette
import dev.wadackel.limenty.core.terminal.TerminalTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Global terminal settings: there are no per-host overrides in V1.
 *
 * Exposed as state rather than as a suspending read so a change repaints the live terminal
 * without touching the connection.
 */
data class TerminalPreferences(
    val typography: TerminalTypography = TerminalTypography(),
    /** A preset's name or a [CustomTheme.id]. Read [palette], which falls back when it names nothing. */
    val themeId: String = TerminalPalette.Default.name,
    val customThemes: List<CustomTheme> = emptyList(),
    val keepScreenAwake: Boolean = true,
    val allowPersonalizedLearning: Boolean = false,
    val keySets: List<KeySet> = listOf(KeySet.Default),
    /** The set the bar shows, as stored. Read [keySet] or [keySetIndex], which keep it in range. */
    val activeKeySet: Int = 0,
    val gestures: GestureBindings = GestureBindings.Initial,
    val doubleTapDragZoom: Boolean = false,
    val restoreKeyboardOnReturn: Boolean = true,
) {
    init {
        require(keySets.isNotEmpty()) { "the bar needs at least one key set" }
    }

    /**
     * The default when [themeId] names nothing, rather than failing: a theme removed or renamed by a
     * later version, or a custom one deleted, must not leave the terminal without colours.
     */
    val palette: TerminalPalette
        get() = themeOf(themeId) ?: TerminalPalette.Default

    /** Presets are found by name, which is the id they were stored under before there were others. */
    fun themeOf(id: String): TerminalPalette? = customThemes.firstOrNull { it.id == id }?.palette
        ?: TerminalPalette.All.firstOrNull { it.name == id }

    fun addTheme(theme: CustomTheme): TerminalPreferences = copy(customThemes = customThemes + theme)

    fun updateTheme(id: String, transform: (TerminalPalette) -> TerminalPalette): TerminalPreferences = copy(customThemes = customThemes.map { if (it.id == id) it.copy(palette = transform(it.palette)) else it })

    /** Removing the theme in use puts the terminal back on the default rather than on a neighbour. */
    fun removeTheme(id: String): TerminalPreferences = copy(
        customThemes = customThemes.filterNot { it.id == id },
        themeId = if (themeId == id) TerminalPalette.Default.name else themeId,
    )

    /**
     * Clamped rather than trusted, because the position is stored apart from the list it points
     * into, and a list that came back shorter would otherwise take the whole terminal down.
     */
    val keySetIndex: Int
        get() = activeKeySet.coerceIn(0, keySets.lastIndex)

    val keySet: KeySet
        get() = keySets[keySetIndex]

    /** Wraps at both ends, so two sets are one swipe apart whichever way the swipe goes. */
    fun switchKeySet(delta: Int): TerminalPreferences = copy(activeKeySet = Math.floorMod(keySetIndex + delta, keySets.size))

    fun updateKeySet(index: Int, transform: (KeySet) -> KeySet): TerminalPreferences = copy(keySets = keySets.mapIndexed { at, set -> if (at == index) transform(set) else set })

    /**
     * Takes [order] as the new order of the same sets, keeping the bar on the set it was showing.
     *
     * Found by identity rather than equality: a duplicated set renamed back is equal to its
     * original, and the first equal one would then take over from the one actually in use.
     */
    fun reorderKeySets(order: List<KeySet>): TerminalPreferences {
        val active = order.indexOfFirst { it === keySet }
        return copy(keySets = order, activeKeySet = if (active >= 0) active else activeKeySet)
    }

    fun duplicateKeySet(index: Int, name: String): TerminalPreferences = copy(keySets = keySets + keySets[index].copy(name = name))

    /**
     * Keeps the bar on the set it was showing. When that is the one removed, the set that slides
     * into its place takes over, or the new last one when there is nothing after it.
     */
    fun removeKeySet(index: Int): TerminalPreferences {
        if (keySets.size <= 1 || index !in keySets.indices) return this
        val active = keySetIndex
        val next = when {
            active > index -> active - 1
            active == index -> minOf(index, keySets.lastIndex - 1)
            else -> active
        }
        return copy(keySets = keySets.filterIndexed { at, _ -> at != index }, activeKeySet = next)
    }
}

class TerminalSettings(context: Context, private val scope: CoroutineScope) {
    private val log = TermLog.logger(LogTags.STORAGE)

    // Opened on first use, not here: `getSharedPreferences` reads the file rather than promising
    // to, and both [read] and [write] already run on [Dispatchers.IO].
    private val preferences by lazy { context.getSharedPreferences(NAME, Context.MODE_PRIVATE) }

    /** One writer at a time, so a slower write cannot land after a newer one. */
    private val writes = Mutex()

    /**
     * Null until the stored values have been read, rather than the defaults.
     *
     * Every mutation below applies a transform to what is published and writes the whole result
     * back. Handing out the defaults first would let a change made in that window persist them
     * over the font, palette and gesture bindings the device actually holds.
     */
    private val _values = MutableStateFlow<TerminalPreferences?>(null)
    val values: StateFlow<TerminalPreferences?> = _values.asStateFlow()

    // The hop to IO is inside the coroutine rather than on `async`, so a caller that hands over a
    // scope it controls can decide when the read is allowed to start.
    private val loading: Deferred<Unit> = scope.async {
        _values.value = withContext(Dispatchers.IO) { read() }
    }

    /** Waits for the first read. Tests use it; the screens are withheld until [values] arrives. */
    suspend fun awaitLoaded() {
        loading.await()
    }

    fun update(transform: (TerminalPreferences) -> TerminalPreferences) = onceLoaded { current ->
        _values.value = transform(current)
        persist()
    }

    /**
     * Applies a change without writing it, for a control that produces a stream of them.
     *
     * A slider emits a value per frame, and each one otherwise re-encodes the key bars and the
     * gesture bindings and hands them to shared preferences — dozens of writes for one
     * adjustment. Pair with [flush] when the gesture ends.
     */
    fun updateWithoutWriting(transform: (TerminalPreferences) -> TerminalPreferences) = onceLoaded { current ->
        _values.value = transform(current)
    }

    /** Writes whatever [updateWithoutWriting] has been accumulating. */
    fun flush() = onceLoaded { persist() }

    /**
     * Runs [block] against the stored values, waiting for the first read if it has not landed.
     *
     * Every caller here transforms what is published and writes the whole result back, so one
     * arriving before the file had been read would otherwise transform the defaults and persist
     * them over the real settings. Waiting rather than refusing, because refusing would make the
     * change disappear with nothing said — the same silence in a different place.
     *
     * The waiting path does not order what it resumes. It does not need to while the screens are
     * withheld until [values] arrives, so nothing that can call these exists before the read
     * lands; a caller added outside that rule would need the ordering added with it.
     */
    private fun onceLoaded(block: (TerminalPreferences) -> Unit) {
        val loaded = _values.value
        if (loaded != null) {
            block(loaded)
            return
        }
        scope.launch {
            awaitLoaded()
            block(checkNotNull(_values.value) { "the read completed without publishing a value" })
        }
    }

    /**
     * Writes what is published, read inside the lock rather than captured at the call.
     *
     * Two writes launched in quick succession take the lock in whichever order the dispatcher
     * gives them, so a captured snapshot lets an older one land last and put every key back.
     * Reading here means whoever wins writes the newest state either way.
     */
    private fun persist() {
        scope.launch {
            writes.withLock {
                val values = checkNotNull(_values.value) { "a write reached the disk before the read" }
                withContext(Dispatchers.IO) { write(values) }
            }
        }
    }

    /**
     * The defaults on a failed read, rather than letting it throw.
     *
     * A stored value whose type no longer matches makes `getString` and friends throw, and the
     * published value would then stay null forever — an app that draws nothing at all, which is
     * a worse answer than starting from the defaults the way [HostRepository] does.
     */
    private fun read(): TerminalPreferences = runCatching { readStored() }
        .onFailure { log.e(it, "settings.readFailed") }
        .getOrDefault(TerminalPreferences())

    private fun readStored(): TerminalPreferences {
        val defaults = TerminalPreferences()
        return TerminalPreferences(
            typography = TerminalTypography(
                fontId = preferences.getString(KEY_FONT, defaults.typography.fontId)!!,
                fontSizeSp = preferences.getFloat(KEY_FONT_SIZE, defaults.typography.fontSizeSp),
                lineHeight = preferences.getFloat(KEY_LINE_HEIGHT, defaults.typography.lineHeight),
                letterSpacingSp = preferences.getFloat(KEY_LETTER_SPACING, defaults.typography.letterSpacingSp),
            ),
            themeId = preferences.getString(KEY_PALETTE, defaults.themeId)!!,
            customThemes = preferences.getString(KEY_CUSTOM_THEMES, null)?.let(CustomTheme::decode) ?: defaults.customThemes,
            keepScreenAwake = preferences.getBoolean(KEY_KEEP_AWAKE, defaults.keepScreenAwake),
            allowPersonalizedLearning = preferences.getBoolean(KEY_IME_LEARNING, defaults.allowPersonalizedLearning),
            keySets = preferences.getString(KEY_KEY_SETS, null)?.let(KeySet::decode)
                ?: readBarBeforeKeySets()?.let(::listOf)
                ?: defaults.keySets,
            activeKeySet = preferences.getInt(KEY_ACTIVE_KEY_SET, defaults.activeKeySet),
            gestures = readGestures() ?: defaults.gestures,
            doubleTapDragZoom = preferences.getBoolean(KEY_DOUBLE_TAP_DRAG, defaults.doubleTapDragZoom),
            restoreKeyboardOnReturn = preferences.getBoolean(KEY_RESTORE_KEYBOARD, defaults.restoreKeyboardOnReturn),
        )
    }

    private fun write(values: TerminalPreferences) {
        preferences.edit()
            .putString(KEY_FONT, values.typography.fontId)
            .putFloat(KEY_FONT_SIZE, values.typography.fontSizeSp)
            .putFloat(KEY_LINE_HEIGHT, values.typography.lineHeight)
            .putFloat(KEY_LETTER_SPACING, values.typography.letterSpacingSp)
            .putString(KEY_PALETTE, values.themeId)
            .putString(KEY_CUSTOM_THEMES, CustomTheme.encode(values.customThemes))
            .putBoolean(KEY_KEEP_AWAKE, values.keepScreenAwake)
            .putBoolean(KEY_IME_LEARNING, values.allowPersonalizedLearning)
            .putString(KEY_KEY_SETS, KeySet.encode(values.keySets))
            .putInt(KEY_ACTIVE_KEY_SET, values.activeKeySet)
            .putString(KEY_GESTURES, encodeGestures(values.gestures))
            .putBoolean(KEY_DOUBLE_TAP_DRAG, values.doubleTapDragZoom)
            .putBoolean(KEY_RESTORE_KEYBOARD, values.restoreKeyboardOnReturn)
            .apply()
    }

    /**
     * The single bar stored before there were sets, as the set it becomes.
     *
     * Read on every launch until a write replaces it with [KEY_KEY_SETS], and left on disk after
     * that rather than removed: an older build installed over this one still finds the bar it wrote.
     */
    private fun readBarBeforeKeySets(): KeySet? {
        val scrolling = preferences.getString(KEY_AUX_KEYS, null)
        val pinned = preferences.getString(KEY_PINNED_KEYS, null)
        if (scrolling == null && pinned == null) return null
        return KeySet(
            name = KeySet.DEFAULT_NAME,
            scrolling = scrolling?.let(AuxiliaryKey::decodeBar) ?: AuxiliaryKey.DefaultScrolling,
            pinned = pinned?.let(AuxiliaryKey::decodeBar) ?: AuxiliaryKey.DefaultPinned,
        )
    }

    /**
     * Stored as gesture-to-behaviour ids, or as the steps themselves for a gesture that sends keys.
     * An entry that cannot be read falls back to None rather than failing the whole read, so a
     * binding removed in a later version cannot strand the other settings.
     */
    private fun readGestures(): GestureBindings? {
        val encoded = preferences.getString(KEY_GESTURES, null) ?: return null
        return runCatching {
            var bindings = GestureBindings(emptyMap())
            JSONObject(encoded).let { json ->
                Gesture.entries.forEach { gesture ->
                    val stored = json.opt(gesture.name) ?: return@forEach
                    bindings = bindings.with(gesture, decodeGesture(stored))
                }
            }
            bindings
        }.getOrNull()
    }

    private fun decodeGesture(stored: Any): GestureBehaviour = when (stored) {
        is JSONArray -> runCatching { GestureBehaviour.SendKeys(KeyStepJson.decodeAll(stored)) }
            .getOrNull()
            ?.takeIf { it.steps.isNotEmpty() }
            ?: GestureBehaviour.None
        is String -> GestureBehaviour.byId(stored)
        else -> GestureBehaviour.None
    }

    private fun encodeGestures(bindings: GestureBindings): String {
        val json = JSONObject()
        bindings.entries().forEach { (gesture, behaviour) -> json.put(gesture.name, encodeGesture(behaviour)) }
        return json.toString()
    }

    /**
     * One key is written as the id an earlier version wrote for it, and only longer keys as steps:
     * a build from before steps, installed over this one, then still finds the presets it offered
     * rather than reading every key binding as nothing.
     */
    private fun encodeGesture(behaviour: GestureBehaviour): Any {
        if (behaviour !is GestureBehaviour.SendKeys) return behaviour.id
        val press = behaviour.steps.singleOrNull() as? KeyStep.Press
        val key = press?.key
        return when {
            press == null -> KeyStepJson.encodeAll(behaviour.steps)
            key != null -> "key:${key.name}:${press.modifiers.bits}"
            press.codePoint > 0 && !press.modifiers.isEmpty -> "chord:${press.codePoint}:${press.modifiers.bits}"
            else -> KeyStepJson.encodeAll(behaviour.steps)
        }
    }

    private companion object {
        const val NAME = "limenty-settings"
        const val KEY_FONT = "font"
        const val KEY_FONT_SIZE = "fontSize"
        const val KEY_LINE_HEIGHT = "lineHeight"
        const val KEY_LETTER_SPACING = "letterSpacing"
        const val KEY_PALETTE = "palette"
        const val KEY_CUSTOM_THEMES = "customThemes"
        const val KEY_IME_LEARNING = "imeLearning"
        const val KEY_KEEP_AWAKE = "keepAwake"
        const val KEY_AUX_KEYS = "auxKeys"
        const val KEY_PINNED_KEYS = "pinnedKeys"
        const val KEY_KEY_SETS = "keySets"
        const val KEY_ACTIVE_KEY_SET = "activeKeySet"
        const val KEY_GESTURES = "gestures"
        const val KEY_DOUBLE_TAP_DRAG = "doubleTapDrag"
        const val KEY_RESTORE_KEYBOARD = "restoreKeyboard"
    }
}
