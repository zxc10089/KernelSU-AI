package me.weishu.kernelsu.ui.screen.aiassistant

/**
 * Entry requests the console can be opened with. Navigation only carries this token, the copy itself is
 * resolved inside the console so a route never holds localized text.
 */
object AiConsoleKickoff {
    /** Asked for by the superuser page: review the real root grants through get_root_app_list. */
    const val ROOT_REVIEW = "root_review"

    /** Asked for by the module page: explain a conflict that was found locally. */
    const val MODULE_CONFLICT = "module_conflict"

    /** Asked for by the module maker: draft a module.prop and its files for the user to copy in. */
    const val MODULE_DRAFT = "module_draft"
}
