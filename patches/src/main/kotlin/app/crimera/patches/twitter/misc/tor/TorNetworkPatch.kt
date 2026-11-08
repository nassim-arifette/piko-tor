/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.misc.tor

import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.enableSettings
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private val torCompanionVisibilityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val queries = document.getElementsByTagName("queries").item(0)
                ?: document.createElement("queries").also { manifest.appendChild(it) }
            val hasCompanion = (0 until queries.childNodes.length).any {
                val node = queries.childNodes.item(it)
                node is Element && node.tagName == "package" &&
                    node.getAttribute("android:name") == "app.piko.tor"
            }
            if (!hasCompanion) {
                queries.appendChild(document.createElement("package").apply {
                    setAttribute("android:name", "app.piko.tor")
                })
            }
        }
    }
}

@Suppress("unused")
val torNetworkPatch = bytecodePatch(
    name = "Tor network companion",
    description = "Opens the experimental Piko Tor companion to turn Tor on or off for X only. Requires a separate APK. Android VPN lockdown is optional and can block other apps. Does not certify leak prevention.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_X)
    dependsOn(settingsPatch, torCompanionVisibilityPatch)
    execute {
        enableSettings("enableTorNetwork")
    }
}
