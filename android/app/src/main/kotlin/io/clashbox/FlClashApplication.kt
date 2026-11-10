package io.clashbox

import android.content.Context
import io.clashbox.common.GlobalState
import io.nekohasekai.sfa.Application

class FlClashApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        GlobalState.init(this)
    }
}
