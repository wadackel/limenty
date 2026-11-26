package dev.wadackel.limenty.data

import androidx.annotation.DrawableRes
import dev.wadackel.limenty.R

/** The headings the icon page lists the icons under, in the order it lists them. */
enum class KeyIconCategory(val title: String) {
    Edit("Edit"),
    Windows("Windows & panes"),
    Run("Run"),
    Move("Move"),
    Code("Git & code"),
    Shell("Shell"),
    Marks("Marks"),
    Interface("Interface"),
}

/**
 * An icon a key or a key group can show in place of its label, from a fixed set of Lucide icons the
 * app ships.
 *
 * Saved by [id], Lucide's own name for the icon, rather than by the entry's name or position, so the
 * list can be reordered here without changing what a saved bar means. Adding an icon is safe;
 * removing one turns the keys that used it back into their names, since an id this build does not
 * know is read as no icon.
 *
 * [label] is the name the icon gives a key that has no name of its own.
 */
enum class KeyIcon(val id: String, val label: String, val category: KeyIconCategory, @get:DrawableRes val drawable: Int) {
    COPY("copy", "Copy", KeyIconCategory.Edit, R.drawable.lucide_copy),
    CLIPBOARD_PASTE("clipboard-paste", "Paste", KeyIconCategory.Edit, R.drawable.lucide_clipboard_paste),
    SCISSORS("scissors", "Cut", KeyIconCategory.Edit, R.drawable.lucide_scissors),
    UNDO_2("undo-2", "Undo", KeyIconCategory.Edit, R.drawable.lucide_undo_2),
    REDO_2("redo-2", "Redo", KeyIconCategory.Edit, R.drawable.lucide_redo_2),
    SEARCH("search", "Search", KeyIconCategory.Edit, R.drawable.lucide_search),
    REPLACE("replace", "Replace", KeyIconCategory.Edit, R.drawable.lucide_replace),
    SAVE("save", "Save", KeyIconCategory.Edit, R.drawable.lucide_save),
    ERASER("eraser", "Clear", KeyIconCategory.Edit, R.drawable.lucide_eraser),
    TRASH("trash", "Delete", KeyIconCategory.Edit, R.drawable.lucide_trash),
    APP_WINDOW("app-window", "New window", KeyIconCategory.Windows, R.drawable.lucide_app_window),
    COLUMNS_2("columns-2", "Split vertical", KeyIconCategory.Windows, R.drawable.lucide_columns_2),
    ROWS_2("rows-2", "Split horizontal", KeyIconCategory.Windows, R.drawable.lucide_rows_2),
    MAXIMIZE_2("maximize-2", "Zoom", KeyIconCategory.Windows, R.drawable.lucide_maximize_2),
    MINIMIZE_2("minimize-2", "Unzoom", KeyIconCategory.Windows, R.drawable.lucide_minimize_2),
    X("x", "Close", KeyIconCategory.Windows, R.drawable.lucide_x),
    CHEVRONS_LEFT("chevrons-left", "Previous", KeyIconCategory.Windows, R.drawable.lucide_chevrons_left),
    CHEVRONS_RIGHT("chevrons-right", "Next", KeyIconCategory.Windows, R.drawable.lucide_chevrons_right),
    ARROW_LEFT_RIGHT("arrow-left-right", "Swap", KeyIconCategory.Windows, R.drawable.lucide_arrow_left_right),
    LAYOUT_GRID("layout-grid", "Layout", KeyIconCategory.Windows, R.drawable.lucide_layout_grid),
    OCTAGON_X("octagon-x", "Interrupt", KeyIconCategory.Run, R.drawable.lucide_octagon_x),
    PAUSE("pause", "Pause", KeyIconCategory.Run, R.drawable.lucide_pause),
    PLAY("play", "Run", KeyIconCategory.Run, R.drawable.lucide_play),
    SQUARE("square", "Stop", KeyIconCategory.Run, R.drawable.lucide_square),
    REFRESH_CW("refresh-cw", "Reload", KeyIconCategory.Run, R.drawable.lucide_refresh_cw),
    ROTATE_CCW("rotate-ccw", "Reset", KeyIconCategory.Run, R.drawable.lucide_rotate_ccw),
    LOG_OUT("log-out", "Exit", KeyIconCategory.Run, R.drawable.lucide_log_out),
    POWER("power", "Power", KeyIconCategory.Run, R.drawable.lucide_power),
    HOUSE("house", "Home", KeyIconCategory.Move, R.drawable.lucide_house),
    ARROW_UP_TO_LINE("arrow-up-to-line", "Top", KeyIconCategory.Move, R.drawable.lucide_arrow_up_to_line),
    ARROW_DOWN_TO_LINE("arrow-down-to-line", "Bottom", KeyIconCategory.Move, R.drawable.lucide_arrow_down_to_line),
    CLOCK_ARROW_LEFT("clock-arrow-left", "History", KeyIconCategory.Move, R.drawable.lucide_clock_arrow_left),
    BOOKMARK("bookmark", "Bookmark", KeyIconCategory.Move, R.drawable.lucide_bookmark),
    LIST("list", "List", KeyIconCategory.Move, R.drawable.lucide_list),
    LIST_TREE("list-tree", "Tree", KeyIconCategory.Move, R.drawable.lucide_list_tree),
    GIT_BRANCH("git-branch", "Branch", KeyIconCategory.Code, R.drawable.lucide_git_branch),
    GIT_COMMIT_HORIZONTAL("git-commit-horizontal", "Commit", KeyIconCategory.Code, R.drawable.lucide_git_commit_horizontal),
    GIT_PULL_REQUEST("git-pull-request", "Pull request", KeyIconCategory.Code, R.drawable.lucide_git_pull_request),
    GIT_MERGE("git-merge", "Merge", KeyIconCategory.Code, R.drawable.lucide_git_merge),
    GIT_COMPARE("git-compare", "Diff", KeyIconCategory.Code, R.drawable.lucide_git_compare),
    CODE("code", "Code", KeyIconCategory.Code, R.drawable.lucide_code),
    BRACES("braces", "Braces", KeyIconCategory.Code, R.drawable.lucide_braces),
    BUG("bug", "Debug", KeyIconCategory.Code, R.drawable.lucide_bug),
    TERMINAL("terminal", "Prompt", KeyIconCategory.Shell, R.drawable.lucide_terminal),
    SQUARE_TERMINAL("square-terminal", "Terminal", KeyIconCategory.Shell, R.drawable.lucide_square_terminal),
    FOLDER("folder", "Folder", KeyIconCategory.Shell, R.drawable.lucide_folder),
    FILE_TEXT("file-text", "File", KeyIconCategory.Shell, R.drawable.lucide_file_text),
    SERVER("server", "Server", KeyIconCategory.Shell, R.drawable.lucide_server),
    KEY_ROUND("key-round", "Key", KeyIconCategory.Shell, R.drawable.lucide_key_round),
    CPU("cpu", "Processes", KeyIconCategory.Shell, R.drawable.lucide_cpu),
    ACTIVITY("activity", "Monitor", KeyIconCategory.Shell, R.drawable.lucide_activity),
    STAR("star", "Star", KeyIconCategory.Marks, R.drawable.lucide_star),
    ZAP("zap", "Bolt", KeyIconCategory.Marks, R.drawable.lucide_zap),
    FLAG("flag", "Flag", KeyIconCategory.Marks, R.drawable.lucide_flag),
    HEART("heart", "Heart", KeyIconCategory.Marks, R.drawable.lucide_heart),
    BELL("bell", "Bell", KeyIconCategory.Marks, R.drawable.lucide_bell),
    TAG("tag", "Tag", KeyIconCategory.Marks, R.drawable.lucide_tag),
    HASH("hash", "Hash", KeyIconCategory.Marks, R.drawable.lucide_hash),
    SPARKLES("sparkles", "Sparkles", KeyIconCategory.Marks, R.drawable.lucide_sparkles),
    MENU("menu", "Menu", KeyIconCategory.Interface, R.drawable.lucide_menu),
    SETTINGS("settings", "Settings", KeyIconCategory.Interface, R.drawable.lucide_settings),
    KEYBOARD("keyboard", "Keyboard", KeyIconCategory.Interface, R.drawable.lucide_keyboard),
    ELLIPSIS("ellipsis", "More", KeyIconCategory.Interface, R.drawable.lucide_ellipsis),
    EYE("eye", "View", KeyIconCategory.Interface, R.drawable.lucide_eye),
    LOCK("lock", "Lock", KeyIconCategory.Interface, R.drawable.lucide_lock),
    ;

    companion object {
        /** Null for an id this build does not ship, such as one a newer build saved. */
        fun byId(id: String): KeyIcon? = entries.firstOrNull { it.id == id }
    }
}
