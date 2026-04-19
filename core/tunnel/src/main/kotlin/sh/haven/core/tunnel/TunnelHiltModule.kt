package sh.haven.core.tunnel

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TunnelHiltModule {
    @Binds
    @Singleton
    abstract fun bindTunnelFactory(impl: DefaultTunnelFactory): TunnelFactory
}
