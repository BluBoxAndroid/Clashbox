package io.clashbox

import io.clashbox.core.SingBoxCore

// Bridges the Flutter service plugin to the sing-box core implementation.
object ServiceController {
    fun setEventListener(listener: ((ByteArray) -> Unit)?) {
        SingBoxCore.eventSink = listener
    }

    fun invokeMethod(data: String, callback: (ByteArray) -> Unit) {
        SingBoxCore.invoke(data, callback)
    }
}
