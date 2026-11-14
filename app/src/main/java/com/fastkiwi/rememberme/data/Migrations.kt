package com.fastkiwi.rememberme.data

import com.fastkiwi.rememberme.core.AppState

/**
 * Data migrations between app versions (PRD P0.5). The data stays the Windows app's data.json
 * (version 1); this schema number tracks what the Android app has added on top of it. Each step must
 * keep every reminder; [Store.migrate] keeps a copy first and refuses a step that loses one.
 */
object Migrations {
    /** The schema this build writes. 0.2.0 does not change the data, so there are no steps yet. */
    const val CURRENT = 1

    class Step(val from: Int, val to: Int, val apply: (AppState) -> AppState)

    val steps: List<Step> = emptyList()
}
