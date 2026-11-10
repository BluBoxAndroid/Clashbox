package io.clashbox.core

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application

// Preloads the libbox native library. The full libbox setup runs later,
// once the Dart side sends core.init with the app home directory.
object Core {
    @Volatile
    private var loaded = false

    fun load() {
        if (loaded) {
            return
        }
        loaded = true
        runCatching {
            System.loadLibrary("box")
        }
        runCatching {
            Libbox.version()
        }
        runCatching {
            Application.application
        }
    }
}
