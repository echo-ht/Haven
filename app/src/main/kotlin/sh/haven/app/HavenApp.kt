package sh.haven.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application() {

    @Inject lateinit var mcpServer: sh.haven.app.agent.McpServer

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // Register Shizuku binder listeners early so the async callback
        // has time to fire before any UI checks isShizukuAvailable().
        sh.haven.core.local.WaylandSocketHelper.initShizukuListeners()
        // Start the local MCP server for agent access — loopback only,
        // stateless Streamable HTTP, read-only tool set in v1.
        mcpServer.start()
    }
}
