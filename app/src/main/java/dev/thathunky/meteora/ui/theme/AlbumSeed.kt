package dev.thathunky.meteora.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import com.materialkolor.ktx.themeColorOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seed color for the album-art theme (PLAN.md Q12), from a small software bitmap of the art. */
@Singleton
class AlbumSeedExtractor @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = LinkedHashMap<String, Color>()

    suspend fun seedFor(url: String): Color? {
        synchronized(cache) { cache[url]?.let { return it } }
        val req = ImageRequest.Builder(context)
            .data(url)
            .size(Size(128, 128))
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return null
        val bmp = (result.image as? BitmapImage)?.bitmap ?: return null
        val seed = withContext(Dispatchers.Default) { bmp.asImageBitmap().themeColorOrNull() } ?: return null
        synchronized(cache) {
            cache[url] = seed
            if (cache.size > 64) cache.remove(cache.keys.first())
        }
        return seed
    }
}
