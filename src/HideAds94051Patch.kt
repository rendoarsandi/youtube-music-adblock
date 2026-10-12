// Experimental source only. Not compiled or verified during playback.
// Target identity: base APK SHA-256
// 891aaeee53805d45e6030b387800f96d696b43ae0c05ca1b7f17884fc2c80f82
// Adapted from the official ReVanced music ad fingerprint and hook:
// https://gitlab.com/ReVanced/revanced-patches/-/tree/main/patches/src/main/kotlin/app/revanced/patches/music/ad/video
// Upstream project license: GPL-3.0. Local change: force the hook's input false,
// without adding a settings toggle or an extension dependency.
package local.youtube.music

import app.revanced.patcher.composingFirstMethod
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.opcodes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private val BytecodePatchContext.adParentMatch by composingFirstMethod(
    "maybeRegenerateCpnAndStatsClient called unexpectedly, but no error.",
) {
    opcodes(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IGET_OBJECT,
    )
}

@Suppress("unused")
val experimentalMusicAds94051Patch = bytecodePatch(
    name = "Experimental music ads hook for 9.40.51",
    description = "Forces the candidate ad-state input false; playback behavior is unverified.",
) {
    compatibleWith("com.google.android.apps.youtube.music"("9.40.51"))

    apply {
        // In the inspected artifact this resolves to Lacec;->d(Z)V.
        // Inserting a constant preserves its field write and callback.
        navigate(adParentMatch.immutableMethod)
            .to(adParentMatch[0] + 1)
            .stop()
            .addInstructions(0, "const/4 p1, 0x0")
    }
}
