package com.fieldmaxx.app

import android.app.Application

class FieldmaxxApp : Application() {
    /** Built on first use, so a process that never needs it (a Robolectric test, say) never builds it. */
    val graph: AppGraph by lazy { AppGraph(this) }
}
