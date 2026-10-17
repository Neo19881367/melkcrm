package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest

abstract class ActivityResultContract<I, O>

class ActivityResultContracts private constructor() {
    class RequestPermission : ActivityResultContract<String, Boolean>()
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>()
    open class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
    }
}
