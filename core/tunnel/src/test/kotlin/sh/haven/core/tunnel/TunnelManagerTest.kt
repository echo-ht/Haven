package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.TunnelConfigRepository

class TunnelManagerTest {

    @Test
    fun getTunnelReturnsNullWhenConfigMissing() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("missing") } returns null
        val manager = TunnelManager(repo)

        assertNull(manager.getTunnel("missing"))
    }

    @Test
    fun getTunnelCachesSameInstanceForSameConfigId() = runTest {
        // Uses TAILSCALE (still stub-backed) so the test doesn't try to
        // load the wireguard-go native library — instrumented tests cover
        // the real WG path end-to-end.
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns tailscaleConfig("cfg-1")
        val manager = TunnelManager(repo)

        val a = manager.getTunnel("cfg-1")
        val b = manager.getTunnel("cfg-1")

        assertNotNull(a)
        assertSame("manager should cache tunnels by config id", a, b)
    }

    @Test
    fun tailscaleStubThrowsOnDial() = runTest {
        // Tailscale ships in a follow-up PR (#102 part 3). Until then its
        // backend is a NotImplementedTunnel that fails loudly rather than
        // hanging. Guards against regression — if someone wires a real
        // TailscaleTunnel later, update this test to match.
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns tailscaleConfig("cfg-1")
        val manager = TunnelManager(repo)

        val tunnel = manager.getTunnel("cfg-1")!!
        assertThrows(UnsupportedOperationException::class.java) {
            tunnel.dial("example.com", 22, 5_000)
        }
    }

    @Test
    fun releaseDropsCachedTunnel() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns tailscaleConfig("cfg-1")
        val manager = TunnelManager(repo)

        val first = manager.getTunnel("cfg-1")
        manager.release("cfg-1")
        val second = manager.getTunnel("cfg-1")

        // New instance after release.
        org.junit.Assert.assertNotSame(first, second)
    }

    private fun tailscaleConfig(id: String) = TunnelConfig(
        id = id,
        label = "test",
        type = TunnelConfigType.TAILSCALE.name,
        configText = "pretend authkey".toByteArray(),
    )
}
