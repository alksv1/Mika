package com.wangzan101.mika.voice.model

import com.wangzan101.mika.core.model.KeyPoolManager
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.activeApiKey
import com.wangzan101.mika.settings.clientHeaders
import com.wangzan101.mika.settings.hasKey
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.settings.providerKeys
import com.wangzan101.mika.core.model.keyRejectionOf
import com.wangzan101.mika.core.model.withKeyRotation
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface VoiceProviderAccountSource {
    fun provider(id: String): Provider?
    fun activeApiKey(id: String): String?
    fun hasKey(id: String): Boolean
    fun keyRevision(id: String): Long
    fun headers(provider: Provider): Map<String, String>
    /** 这家密钥池里有几把密钥;只有一把时没得轮换。 */
    fun keyCount(id: String): Int = 1
    /** 上游拒了 [key]:和对话模型同一处记账(429 冷却,401/403 耗尽),见 [KeyPoolManager.markRejected]。 */
    fun rejectKey(id: String, key: String, status: Int, retryAfterMs: Long?) {}
}

class ProviderStoreVoiceAccountSource(private val store: ProviderStore) : VoiceProviderAccountSource {
    override fun provider(id: String): Provider? = store.provider(id)

    override fun activeApiKey(id: String): String? =
        store.activeApiKey(id) ?: store.providerKey(id)?.let { KeyPoolManager.shared.parseKeys(it).firstOrNull() }

    override fun hasKey(id: String): Boolean = store.hasKey(id)
    override fun keyRevision(id: String): Long = store.providerKeyRevisions.value[id] ?: 0L
    override fun headers(provider: Provider): Map<String, String> = store.clientHeaders(provider)
    override fun keyCount(id: String): Int = store.providerKeys(id).size
    override fun rejectKey(id: String, key: String, status: Int, retryAfterMs: Long?) =
        KeyPoolManager.shared.markRejected(id, key, status, retryAfterMs)
}

sealed interface ResolvedAccount {
    val credentials: VoiceCredentials
    val credentialsConfigured: Boolean
    val configurationFingerprint: String

    data class ExistingProvider(
        val provider: Provider,
        override val credentials: VoiceCredentials,
        val headers: Map<String, String>,
        val keyRevision: Long,
        override val credentialsConfigured: Boolean = credentials.configured,
    ) : ResolvedAccount {
        override val configurationFingerprint: String
            get() = VoiceFingerprints.account(
                "provider:${provider.id}|${provider.kind}|${provider.baseUrl}|${provider.sessionHeader.orEmpty()}|$keyRevision",
            )

        override fun toString(): String =
            "ExistingProvider(providerId=${provider.id}, baseUrl=${provider.baseUrl}, keyRevision=$keyRevision, configured=$credentialsConfigured)"
    }

    data class VoiceOwned(
        val secretId: String,
        override val credentials: VoiceCredentials,
        override val credentialsConfigured: Boolean = credentials.configured,
        val configurationToken: String = "configured",
    ) : ResolvedAccount {
        override val configurationFingerprint: String
            get() = VoiceFingerprints.account("owned:$secretId|$configurationToken")

        override fun toString(): String = "VoiceOwned(secretId=$secretId, configured=$credentialsConfigured)"
    }

    data object None : ResolvedAccount {
        override val credentials: VoiceCredentials = VoiceCredentials()
        override val credentialsConfigured: Boolean = true
        override val configurationFingerprint: String = VoiceFingerprints.account("none")
    }
}

data class ResolvedVoiceModel(
    val service: VoiceService,
    val model: VoiceModel,
    val account: ResolvedAccount,
    val configurationFingerprint: String,
)

class VoiceAccountResolver(
    private val providers: VoiceProviderAccountSource,
    private val owned: VoiceOwnedAccountSource,
) {
    constructor(store: ProviderStore, owned: VoiceOwnedAccountSource) :
        this(ProviderStoreVoiceAccountSource(store), owned)

    fun resolve(service: VoiceService): ResolvedAccount = when (val source = service.account) {
        is VoiceAccount.ExistingProvider -> {
            val provider = providers.provider(source.providerId) ?: throw VoiceModelError.MissingAccount(source.providerId)
            ResolvedAccount.ExistingProvider(
                provider = provider,
                credentials = VoiceCredentials(apiKey = providers.activeApiKey(provider.id).orEmpty()),
                headers = providers.headers(provider),
                keyRevision = providers.keyRevision(provider.id),
                credentialsConfigured = providers.hasKey(provider.id),
            )
        }
        is VoiceAccount.VoiceOwned -> {
            val credentials = owned.read(source.secretId) ?: throw VoiceModelError.MissingCredentials()
            ResolvedAccount.VoiceOwned(
                secretId = source.secretId,
                credentials = credentials,
                configurationToken = owned.configurationToken(source.secretId),
            )
        }
        VoiceAccount.None -> ResolvedAccount.None
    }

    fun resolve(service: VoiceService, model: VoiceModel): ResolvedVoiceModel {
        if (service.id != model.serviceId) throw VoiceModelError.IncompleteConfig("Voice model belongs to another service")
        val account = resolve(service)
        return ResolvedVoiceModel(
            service = service,
            model = model,
            account = account,
            configurationFingerprint = configurationFingerprint(service, model),
        )
    }

    /** [model] 借用的对话服务商密钥池里有不止一把密钥,值得轮换。自带密钥的语音服务只有一把。 */
    fun pooled(model: ResolvedVoiceModel): Boolean =
        (model.account as? ResolvedAccount.ExistingProvider)?.let { providers.keyCount(it.provider.id) > 1 } == true

    /**
     * 用 [model] 的账号发一次请求。借用对话服务商账号的,走那家的密钥池,和对话模型共用同一份密钥、同一个轮询、
     * 同一份冷却:每次请求向池里现取一把,被拒(429/401/403,见 [keyRejectionOf])就照对话那边记账,换池里下一把重发,
     * 每把最多一次([withKeyRotation])。[mayRetry] 为 false 时(比如流式朗读已经交出了声音)不再换密钥重发。
     * 自带密钥的语音服务原样发一次。
     */
    suspend fun <T> withPooledKey(
        model: ResolvedVoiceModel,
        mayRetry: () -> Boolean = { true },
        send: suspend (ResolvedVoiceModel) -> T,
    ): T {
        val account = model.account as? ResolvedAccount.ExistingProvider ?: return send(model)
        val providerId = account.provider.id
        val resolvedKey = account.credentials.apiKey.ifBlank { null }
        val result = withKeyRotation(
            // 和对话模型的 keySupplier 一样每次请求现取:轮询、跳过冷却中的密钥都在池里做
            nextKey = { providers.activeApiKey(providerId) ?: resolvedKey },
            rejected = { key, retryAfterMs, status -> providers.rejectKey(providerId, key, status, retryAfterMs) },
            rejectionOf = { outcome: Result<T> ->
                outcome.exceptionOrNull()?.takeIf { mayRetry() }?.let(::keyRejectionOf)
            },
            send = { key ->
                val keyed = model.copy(account = account.copy(credentials = VoiceCredentials(apiKey = key.orEmpty())))
                try {
                    Result.success(send(keyed))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
        )
        return result.getOrThrow()
    }

    /**
     * Non-decrypting configuration fingerprint for [model] under [service]. Used by probe gating and UI probe
     * state so that no secret plaintext is ever read just to display or compare progress; decryption stays at
     * connection time only. For provider accounts it keys on the provider's public fields and key revision, and
     * for owned accounts on the secret id and the ciphertext token, never the decrypted credentials.
     */
    fun configurationFingerprint(service: VoiceService, model: VoiceModel): String {
        val accountFingerprint: String
        val providerBaseUrl: String?
        when (val source = service.account) {
            is VoiceAccount.ExistingProvider -> {
                val provider = providers.provider(source.providerId)
                    ?: throw VoiceModelError.MissingAccount(source.providerId)
                accountFingerprint = VoiceFingerprints.account(
                    "provider:${provider.id}|${provider.kind}|${provider.baseUrl}|${provider.sessionHeader.orEmpty()}|${providers.keyRevision(provider.id)}",
                )
                providerBaseUrl = provider.baseUrl
            }
            is VoiceAccount.VoiceOwned -> {
                accountFingerprint = VoiceFingerprints.account("owned:${source.secretId}|${owned.configurationToken(source.secretId)}")
                providerBaseUrl = null
            }
            VoiceAccount.None -> {
                accountFingerprint = VoiceFingerprints.account("none")
                providerBaseUrl = null
            }
        }
        return VoiceFingerprints.model(accountFingerprint, providerBaseUrl, model)
    }

    /** Non-decrypting credential-presence check for UI/profile display; never reads secret plaintext. */
    fun credentialsConfigured(service: VoiceService): Boolean = when (val source = service.account) {
        is VoiceAccount.ExistingProvider -> providers.hasKey(source.providerId)
        is VoiceAccount.VoiceOwned -> owned.configurationToken(source.secretId).isNotBlank()
        VoiceAccount.None -> true
    }
}

private object VoiceFingerprints {
    private val json = Json

    fun account(value: String): String = sha256(value)

    fun model(accountFingerprint: String, providerBaseUrl: String?, model: VoiceModel): String {
        val accountEndpoint = providerBaseUrl
            ?: listOfNotNull(model.stt?.endpoint, model.tts?.endpoint, model.realtime?.endpoint).joinToString("|")
        return sha256(accountFingerprint + "|" + accountEndpoint + "|" + json.encodeToString(model))
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
