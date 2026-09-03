package ch.marty.finreader.util

import android.content.Context
import ch.marty.finreader.BuildConfig

/**
 * What build is actually running — the one thing a sideloaded app cannot take
 * for granted, since nothing stops an older APK from being installed over a
 * newer one by hand.
 */
object AppVersion {

    /** e.g. `0.1.4 (5) · 16be15e`, or `… · 16be15e+` when the tree was dirty. */
    fun full(context: Context): String = "${name(context)} · ${BuildConfig.GIT_SHA}"

    /** e.g. `0.1.4 (5)`. Read from the package manager, so it is the installed truth. */
    fun name(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("?")

    val commit: String get() = BuildConfig.GIT_SHA
}
