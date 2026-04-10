package sh.haven.app.reticulum

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.RnshShellSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChaquopyTransport"

/**
 * Adapter that wraps the legacy blocking [ReticulumBridge] (Chaquopy/Python)
 * behind the new coroutines-first [ReticulumTransport] interface.
 *
 * This exists during the migration window so both the Python and native
 * transports can be swapped via a runtime toggle without changing
 * consumers (ReticulumSessionManager, ConnectionsViewModel).
 */
@Singleton
class ChaquopyReticulumTransport @Inject constructor(
    private val bridge: ChaquopyReticulumBridge,
) : ReticulumTransport {

    override val isInitialised: Boolean
        get() = bridge.isInitialised()

    private val _discovered = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> =
        _discovered.asStateFlow()

    override suspend fun init(
        configDir: String,
        host: String,
        port: Int,
        ifacNetname: String?,
        ifacNetkey: String?,
    ): String = withContext(Dispatchers.IO) {
            // IFAC params ignored — Chaquopy bridge reads IFAC from config file
            val identityHash = bridge.initReticulum(configDir, host, port)
            refreshDiscovered()
            identityHash
        }

    override suspend fun openSession(
        destinationHash: String,
        rows: Int,
        cols: Int,
    ): RnshShellSession = withContext(Dispatchers.IO) {
        // Resolve destination (blocking, up to 15s)
        val resolved = bridge.resolveDestination(destinationHash)
        if (!resolved) {
            throw RuntimeException("Could not resolve destination $destinationHash")
        }

        // Create Python rnsh session
        val sessionId = UUID.randomUUID().toString()
        bridge.createSession(destinationHash, sessionId)

        // Wrap in a ShellSession
        ChaquopyShellSession(sessionId, bridge)
    }

    override suspend fun requestPath(destinationHashHex: String): Boolean =
        withContext(Dispatchers.IO) {
            bridge.requestPath(destinationHashHex)
        }

    override suspend fun probeSideband(configDir: String): Boolean =
        withContext(Dispatchers.IO) {
            bridge.probeSideband(configDir)
        }

    override suspend fun closeAll() = withContext(Dispatchers.IO) {
        bridge.closeAll()
    }

    private fun refreshDiscovered() {
        try {
            val json = bridge.getDiscoveredDestinations()
            val arr = JSONArray(json)
            val list = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                DiscoveredDestination(
                    hash = obj.getString("hash"),
                    hops = obj.optInt("hops", 0),
                )
            }
            _discovered.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh discovered destinations", e)
        }
    }
}

/**
 * Shell session backed by the Chaquopy Python bridge.
 * Converts the blocking readOutput poll loop into a Flow.
 */
private class ChaquopyShellSession(
    override val sessionId: String,
    private val bridge: ReticulumBridge,
) : RnshShellSession {

    override val exitCode = CompletableDeferred<Int>()

    override val isConnected: Boolean
        get() = bridge.isConnected(sessionId)

    override val output: Flow<ByteArray> = flow {
        while (true) {
            val data = bridge.readOutput(sessionId, timeoutMs = 500)
            if (data == null) {
                // Disconnected
                if (!exitCode.isCompleted) {
                    exitCode.complete(0) // Clean exit assumed
                }
                break
            }
            if (data.isNotEmpty()) {
                emit(data)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendInput(data: ByteArray) = withContext(Dispatchers.IO) {
        bridge.sendInput(sessionId, data)
        Unit
    }

    override suspend fun resize(rows: Int, cols: Int) = withContext(Dispatchers.IO) {
        bridge.resizeSession(sessionId, cols, rows)
    }

    override fun close() {
        try {
            bridge.closeSession(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "close failed for $sessionId", e)
        }
        if (!exitCode.isCompleted) {
            exitCode.complete(-1)
        }
    }
}
