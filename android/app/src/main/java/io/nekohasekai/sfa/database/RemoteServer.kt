package io.nekohasekai.sfa.database

// Minimal stand-in for the upstream Room entity: Clashbox is local-only and
// never stores remote servers, but the upstream CommandTarget references
// this type, so it is kept with the same URL helpers.
class RemoteServer(
    var id: Long = 0L,
    var name: String = "",
    var url: String = "",
    var secret: String = "",
) {
    companion object {
        private val schemePrefix = Regex("^https?://", RegexOption.IGNORE_CASE)

        fun connectURL(urlString: String): String {
            val value = urlString.trim().trimEnd('/')
            if (value.isEmpty()) {
                return ""
            }
            if (value.contains(schemePrefix)) {
                return value
            }
            return "http://$value"
        }
    }
}
