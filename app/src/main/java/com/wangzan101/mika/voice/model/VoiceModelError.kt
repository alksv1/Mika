package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsError
import com.wangzan101.mika.voice.ondevice.OnDeviceSttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class VoiceErrorCategory {
    INCOMPLETE_CONFIG,
    MISSING_CREDENTIALS,
    AUTH_FAILED,
    UNSUPPORTED,
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    PROTOCOL_ERROR,
    SYSTEM_UNAVAILABLE,
    CANCELLED,
}

sealed class VoiceModelError(
    val category: VoiceErrorCategory,
    val detail: String,
    cause: Throwable? = null,
) : Exception(detail, cause) {
    val isFailure: Boolean get() = category != VoiceErrorCategory.CANCELLED

    class IncompleteConfig(detail: String) :
        VoiceModelError(VoiceErrorCategory.INCOMPLETE_CONFIG, detail)

    class MissingAccount(accountId: String) :
        VoiceModelError(VoiceErrorCategory.MISSING_CREDENTIALS, "Voice account is no longer available: $accountId")

    class MissingCredentials(detail: String = "Voice account credentials are missing or incomplete") :
        VoiceModelError(VoiceErrorCategory.MISSING_CREDENTIALS, detail)

    class Unsupported(val requested: VoiceCapability, val protocol: String? = null) :
        VoiceModelError(
            VoiceErrorCategory.UNSUPPORTED,
            "Voice capability $requested is not supported${protocol?.let { " for protocol $it" }.orEmpty()}",
        )

    class Protocol(category: VoiceErrorCategory, detail: String, cause: Throwable? = null) :
        VoiceModelError(category, detail, cause)

    class Cancelled(cause: Throwable? = null) :
        VoiceModelError(VoiceErrorCategory.CANCELLED, "Voice operation cancelled", cause)

    /** 选中的(或备援链里的)本机语音包没装、文件不全,或清单里已经没有它;界面提示「需要下载」。 */
    class PackMissing(val packId: String) :
        VoiceModelError(VoiceErrorCategory.INCOMPLETE_CONFIG, "Voice pack is not installed: $packId")

    /**
     * 本机识别失败(文件损坏、内存不足、超时、设备跑不了、模型被释放)。原因链里不带服务商错误([ModelError]):
     * [VoiceFailover.canTryNext] 先看原因链里的 ModelError,本机失败不能被当成网络错误。
     */
    class OnDeviceFailed(detail: String, cause: Throwable? = null) :
        VoiceModelError(VoiceErrorCategory.SYSTEM_UNAVAILABLE, detail, cause?.takeUnless(::hasModelError))

    companion object {
        /** Maps existing protocol errors without changing their request or session implementations. */
        fun from(error: Throwable): VoiceModelError = when (error) {
            is VoiceModelError -> error
            is OnDeviceSttException -> from(error, packId = "")
            is TimeoutCancellationException -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            is CancellationException -> Cancelled(error)
            is SocketTimeoutException -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            is UnknownHostException -> Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
            is ModelError.Auth, is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed ->
                Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
            is ModelError.IllegalHeader -> Protocol(VoiceErrorCategory.INCOMPLETE_CONFIG, detailOf(error), error)
            is ModelError.RateLimited -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
            is ModelError.ModelNotFound, is ModelError.ModelNotAvailable, is ModelError.ToolsUnsupported ->
                Protocol(VoiceErrorCategory.UNSUPPORTED, detailOf(error), error)
            is ModelError.Network -> networkError(error)
            is ModelError.Server -> when (error.status) {
                401, 403 -> Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
                408, 504 -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
                429 -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
                else -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            }
            is ModelError.BadResponse -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            is TtsError.Unsupported -> Protocol(VoiceErrorCategory.UNSUPPORTED, detailOf(error), error)
            is TtsError.BadAudio -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            is VolcRealtimeException -> when (error.kind) {
                VolcRealtimeException.Kind.AUTH -> Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
                VolcRealtimeException.Kind.RATE_LIMITED -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
                VolcRealtimeException.Kind.TIMEOUT -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
                VolcRealtimeException.Kind.NETWORK, VolcRealtimeException.Kind.BACKPRESSURE ->
                    Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
                VolcRealtimeException.Kind.PROTOCOL, VolcRealtimeException.Kind.PROVIDER ->
                    Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            }
            is IOException -> networkError(error)
            is IllegalArgumentException -> Protocol(VoiceErrorCategory.INCOMPLETE_CONFIG, detailOf(error), error)
            else -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
        }

        /** 本机识别的失败:文件不在是「需要下载」,其余都是本机不可用(都换下一个,见 [VoiceFailover])。 */
        fun from(error: OnDeviceSttException, packId: String): VoiceModelError = when (error.kind) {
            OnDeviceSttException.Kind.Missing -> PackMissing(packId)
            OnDeviceSttException.Kind.Corrupt,
            OnDeviceSttException.Kind.OutOfMemory,
            OnDeviceSttException.Kind.Timeout,
            OnDeviceSttException.Kind.Unsupported,
            OnDeviceSttException.Kind.Failed -> OnDeviceFailed(detailOf(error), error)
        }

        private fun hasModelError(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(8).any { it is ModelError }

        private fun networkError(error: Throwable): VoiceModelError {
            val causes = generateSequence(error) { it.cause }
            if (causes.any { it is SocketTimeoutException }) {
                return Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            }
            return Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
        }

        private fun detailOf(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
