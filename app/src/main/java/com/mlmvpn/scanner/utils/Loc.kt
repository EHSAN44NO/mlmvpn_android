package com.mlmvpn.scanner.utils

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.mlmvpn.scanner.R

/**
 * Read a localised string from anywhere, in the language the user picked in Settings.
 *
 * Everything the app shows lives in `res/values/strings.xml` (English, the default) with a
 * translation beside it in `values-fa`. Adding a third language is then one new `values-xx`
 * folder and nothing else -- no source change, no rebuild of the call sites.
 *
 * [androidx.compose.ui.res.stringResource] is the usual way to reach those, and it is still
 * fine to use. This exists because a large share of the app's strings are not in a place a
 * `@Composable` can be called from: inside `remember { }` lambdas, in plain helper functions
 * that return a label, in `when` branches of ordinary code, and in engine, service and manager
 * classes that have no composition at all. [S] works in all of them, so one form covers the
 * whole codebase and nothing has to be restructured just to be translated.
 *
 * ```
 * Text(S(R.string.quick_ready_to_connect))
 * ```
 *
 * The resources are pinned to the chosen locale rather than the device's, which is the whole
 * point of the language picker: the app's own configuration context is what answers, not
 * whatever the phone is set to.
 */
object Loc {

    @Volatile
    private var res: Resources? = null

    /** Called once from the Application, and again whenever the language changes. */
    fun bind(context: Context) {
        res = AppLocaleManager.wrapContext(context.applicationContext).resources
    }

    /**
     * Falls back to the caller's own resources if [bind] has not run yet -- a string is never
     * worth crashing over, and returning an empty label would be a silent, confusing bug.
     */
    fun string(context: Context?, @StringRes id: Int): String =
        (res ?: context?.resources)?.getString(id) ?: ""
}

/** Short form, for the several thousand call sites that just want the text. */
fun S(@StringRes id: Int): String = Loc.string(null, id)

/**
 * With format arguments, for strings that carry a count or a name.
 *
 * Nullable arguments on purpose: most of what gets interpolated is an optional field of a
 * measurement, and `String.format` renders null as "null" rather than throwing -- which is
 * what the Kotlin templates these replaced already did.
 */
fun S(@StringRes id: Int, vararg args: Any?): String =
    Loc.string(null, id).let { if (args.isEmpty()) it else String.format(it, *args) }
