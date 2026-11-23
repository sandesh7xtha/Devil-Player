package com.offline.musicplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ExtractedColors(
    val dominant: Color = Color(0xFF7C4DFF),
    val secondary: Color = Color(0xFF00E5FF),
    val surface: Color = Color(0xFF1E1E24)
)

@Composable
fun rememberDominantColor(
    context: Context,
    artworkUri: Uri?,
    defaultPrimary: Color = Color(0xFF7C4DFF),
    defaultSecondary: Color = Color(0xFF00E5FF)
): ExtractedColors {
    val colorsState = remember(artworkUri) {
        mutableStateOf(ExtractedColors(dominant = defaultPrimary, secondary = defaultSecondary))
    }

    LaunchedEffect(artworkUri) {
        if (artworkUri == null) {
            colorsState.value = ExtractedColors(dominant = defaultPrimary, secondary = defaultSecondary)
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            try {
                val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, artworkUri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.setTargetSampleSize(4) // downsample for fast palette extraction
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, artworkUri)
                }

                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val dominantRgb = palette.getVibrantColor(
                        palette.getDominantColor(defaultPrimary.hashCode())
                    )
                    val darkMutedRgb = palette.getDarkMutedColor(0xFF1E1E24.toInt())
                    val accentRgb = palette.getLightVibrantColor(
                        palette.getMutedColor(defaultSecondary.hashCode())
                    )

                    colorsState.value = ExtractedColors(
                        dominant = Color(dominantRgb),
                        secondary = Color(accentRgb),
                        surface = Color(darkMutedRgb)
                    )
                }
            } catch (_: Exception) {
                colorsState.value = ExtractedColors(dominant = defaultPrimary, secondary = defaultSecondary)
            }
        }
    }

    return colorsState.value
}
