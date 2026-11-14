package app.pureiptv.ui.components

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult

/**
 * Image addresses (logos, posters, backdrops) come from playlists and providers. Only http(s) ones
 * are loaded: Coil would otherwise also open `file://`, `content://` and `android.resource://`
 * addresses, and a playlist has no business pointing at those on this device.
 */
private object HttpImagesOnly : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val address = when (val data = request.data) {
            is String -> data
            is coil3.Uri -> data.toString()
            is android.net.Uri -> data.toString()
            else -> null
        }?.trim()
        if (address != null && !address.startsWith("http://", ignoreCase = true) && !address.startsWith("https://", ignoreCase = true)) {
            return ErrorResult(null, request, IllegalArgumentException("Unsupported image address"))
        }
        return chain.proceed()
    }
}

/** Call once at app start, before anything shows an image. */
fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(HttpImagesOnly) }
            .build()
    }
}
