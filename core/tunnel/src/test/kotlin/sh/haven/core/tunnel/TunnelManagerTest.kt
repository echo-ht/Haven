package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.repository.TunnelConfigRepository

/**
 * Unit tests for the manager's caching + lookup behaviour. Real tunnel
 * creation is behind [TunnelFactory]; these tests inject a counting fake
 * so they don't hit libgojni.so (not loadable in a plain JVM test).
 */
class TunnelManagerTest {

    @Test
    fun getTunnelReturnsNullWhenConfigMissing() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("missing") } returns null
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        assertNull(manager.getTunnel("missing"))
        org.junit.Assert.assertEquals(0, factory.createCount)
    }

    @Test
    fun getTunnelCachesSameInstanceForSameConfigId() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val a = manager.getTunnel("cfg-1")
        val b = manager.getTunnel("cfg-1")

        assertNotNull(a)
        assertSame("manager should cache tunnels by config id", a, b)
        org.junit.Assert.assertEquals(
            "factory called once even though getTunnel invoked twice",
            1,
            factory.createCount,
        )
    }

    @Test
    fun releaseDropsCachedTunnelAndClosesIt() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val first = manager.getTunnel("cfg-1") as FakeTunnel
        manager.release("cfg-1")
        val second = manager.getTunnel("cfg-1")

        org.junit.Assert.assertTrue("release should close the evicted tunnel", first.closed)
        org.junit.Assert.assertNotSame(
            "new instance returned after release",
            first,
            second,
        )
    }

    private fun wgConfig(id: String) = TunnelConfig(
        id = id,
        label = "test",
        type = TunnelConfigType.WIREGUARD.name,
        configText = "[Interface]".toByteArray(),
    )

    private class FakeTunnel : Tunnel {
        var closed = false
        override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection =
            throw UnsupportedOperationException("fake")

        override fun close() { closed = true }
    }

    private class CountingFactory : TunnelFactory {
        var createCount = 0
        override fun create(config: TunnelConfig): Tunnel {
            createCount++
            return FakeTunnel()
        }
    }
}
