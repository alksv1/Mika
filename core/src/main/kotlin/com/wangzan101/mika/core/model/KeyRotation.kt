package com.wangzan101.mika.core.model

/** 上游拒了这把密钥:HTTP 状态(429 限流;401/403 无效或被封)和它建议的冷却。 */
data class KeyRejection(val status: Int, val retryAfterMs: Long?)

/**
 * 一次逻辑请求按密钥池轮换:[send] 的结果经 [rejectionOf] 判为「这把密钥被拒」时,先交 [rejected]
 * (接线处按状态码记 429 冷却或 401/403 耗尽),再向 [nextKey] 要下一把重发;每把密钥最多试一次,
 * 池里没有别的可用密钥就把最后一次的结果原样交回。预算、取消与网络错误不在这里处理。
 */
suspend fun <T> withKeyRotation(
    nextKey: () -> String?,
    rejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?,
    rejectionOf: (T) -> KeyRejection?,
    send: suspend (String?) -> T,
): T {
    var key = nextKey()
    val attempted = mutableSetOf(key)
    while (true) {
        val result = send(key)
        val rejection = rejectionOf(result) ?: return result
        if (key == null || rejected == null) return result
        rejected(key, rejection.retryAfterMs, rejection.status)
        val next = nextKey()
        if (next.isNullOrBlank() || !attempted.add(next)) return result
        key = next
    }
}

/** 对话适配器那条路:按原始 HTTP 状态判断拒绝。拒绝回调带上状态码,接线处据此区分 429 冷却与 401/403 耗尽。 */
internal suspend fun requestWithKeyRotation(
    nextKey: () -> String?,
    rejected: ((String, Long?, Int) -> Unit)?,
    send: suspend (String?) -> RawResponse,
): RawResponse = withKeyRotation(nextKey, rejected, { response ->
    if (response.status in REJECTED_KEY_STATUSES) {
        KeyRejection(response.status, HttpSupport.retryAfterMs(response.retryAfter, response.body))
    } else null
}, send)

/**
 * 已经映射成 [ModelError] 抛出来的失败(语音转写、朗读等不经 [RawResponse] 的调用)里,是不是「这把密钥被拒」。
 * 沿 cause 链找:语音层会把 [ModelError] 包一层再抛。和 [requestWithKeyRotation] 认同样的三种状态。
 */
fun keyRejectionOf(error: Throwable): KeyRejection? = generateSequence(error) { it.cause }
    .take(MAX_CAUSE_DEPTH)
    .firstNotNullOfOrNull { cause ->
        when (cause) {
            is ModelError.RateLimited -> KeyRejection(429, cause.retryAfterMs)
            is ModelError.Auth -> KeyRejection(401, null)
            is ModelError.Server -> cause.status.takeIf { it in REJECTED_KEY_STATUSES }?.let { KeyRejection(it, null) }
            else -> null
        }
    }

private val REJECTED_KEY_STATUSES = setOf(401, 403, 429)
private const val MAX_CAUSE_DEPTH = 8
