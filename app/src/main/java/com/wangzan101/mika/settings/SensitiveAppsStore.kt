package com.wangzan101.mika.settings

import com.wangzan101.mika.core.sensitive.SensitiveAppsPolicy
import com.wangzan101.mika.core.sensitive.SensitiveAppsState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 用户自己的敏感 App 名单,默认空;空名单时整个功能不启用。
 * 只存包名,原子写;文件坏了按空名单处理并在 [SensitiveAppsState.available] 上报,不因为读不出就拦住任务。
 * 改一次就从文件读到的最新状态改,设置页和任务里读的是同一份([current])。
 */
class SensitiveAppsStore(private val file: File) : SensitiveAppsPolicy {
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    override fun current(): SensitiveAppsState = mutable.value

    /** 把 [packageName] 加进或移出名单;读不出的状态会在下一次写入时修好。 */
    @Synchronized fun setSensitive(packageName: String, sensitive: Boolean) {
        val pkg = packageName.trim()
        require(pkg.isNotEmpty()) { "package name must not be blank" }
        val packages = current().packages.let { if (sensitive) it + pkg else it - pkg }
        persist(SensitiveAppsState(packages))
    }

    /** 整份替换(设置页的批量操作、测试用);空白包名丢掉。 */
    @Synchronized fun replace(packages: Set<String>) {
        persist(SensitiveAppsState(packages.map(String::trim).filter(String::isNotEmpty).toSet()))
    }

    @Synchronized fun reset() = persist(SensitiveAppsState())

    private fun persist(next: SensitiveAppsState) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", VERSION)
            put("packages", JsonArray(next.packages.sorted().map(::JsonPrimitive)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // 写成功后文件一定是可读的:读不出的状态就此修好。
        mutable.value = next.copy(available = true)
    }

    private fun load(): SensitiveAppsState {
        if (!file.exists()) return SensitiveAppsState()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == VERSION)
            val packages = o.getValue("packages").jsonArray
                .map { it.jsonPrimitive.content }
                .filter(String::isNotBlank)
                .toSet()
            SensitiveAppsState(packages)
        }.getOrElse { SensitiveAppsState(available = false) }
    }

    private companion object {
        const val VERSION = 1
    }
}
