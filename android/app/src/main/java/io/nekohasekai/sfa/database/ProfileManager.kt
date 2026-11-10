package io.nekohasekai.sfa.database

class TypedProfile(val path: String)

class Profile(val name: String, val typed: TypedProfile)

// The core plugin registers the merged sing-box config file here before the
// service starts; BoxService reads the file content from typed.path.
object ProfileManager {
    @Volatile
    var currentName: String = ""

    @Volatile
    var currentPath: String? = null

    fun get(id: Long): Profile? {
        val path = currentPath ?: return null
        return Profile(currentName, TypedProfile(path))
    }
}
