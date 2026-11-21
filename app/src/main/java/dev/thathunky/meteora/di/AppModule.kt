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
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import dev.thathunky.meteora.ytm.YtmAuthProvider
import dev.thathunky.meteora.ytm.YtmClient

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

    /** HTTPS-only client for YouTube Music: no cleartext connection specs at all. */
    @Provides @Singleton
    fun ytmClient(okHttp: OkHttpClient, auth: YtmAuthProvider): YtmClient {
        val https = okHttp.newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return YtmClient(HttpClient(OkHttp.create { preconfigured = https }) { expectSuccess = false }, auth)
    }

    @Provides @Singleton
    fun ytmAuth(store: dev.thathunky.meteora.ytm.YtmAuthStore): YtmAuthProvider = store

    @Provides @Singleton
    fun jsSolver(solver: dev.thathunky.meteora.stream.WebViewJsSolver): dev.thathunky.meteora.stream.JsSolver = solver
}
