package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

abstract class ActivityResultLauncher<I> {
    fun launch(input: I) {}
}

class PickVisualMediaRequest internal constructor()

fun PickVisualMediaRequest(
    mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
): PickVisualMediaRequest = TODO()
