package dev.wadackel.limenty.core.common.log

/**
 * Subsystem tags. Keeping them centralized makes `adb logcat -s` filtering predictable
 * and keeps grep patterns stable across the codebase.
 */
object LogTags {
    const val LIFECYCLE = "Lifecycle"
    const val IME = "Ime"
    const val KEY = "Key"
    const val INPUT = "Input"
    const val TERMINAL = "Terminal"
    const val RENDER = "Render"
    const val GRAPHICS = "Graphics"
    const val TRANSPORT = "Transport"
    const val SSH = "Ssh"
    const val SESSION = "Session"
    const val RESIZE = "Resize"
    const val GESTURE = "Gesture"
    const val CLIPBOARD = "Clipboard"
    const val SETTINGS = "Settings"
    const val STORAGE = "Storage"
    const val DISCOVERY = "Discovery"
}
