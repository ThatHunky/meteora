package dev.thathunky.meteora.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.thathunky.meteora.companion.CompanionClient
import dev.thathunky.meteora.companion.RealtimeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // websocket + long-poll auth request
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun companionClient(okHttp: OkHttpClient): CompanionClient =
        CompanionClient(CompanionClient.defaultHttpClient(OkHttp.create { preconfigured = okHttp }))

    @Provides @Singleton
    fun realtimeClient(okHttp: OkHttpClient): RealtimeClient = RealtimeClient(okHttp)
}
