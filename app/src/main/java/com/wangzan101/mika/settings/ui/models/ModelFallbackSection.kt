package com.wangzan101.mika.settings.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.MAX_FALLBACKS
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.FallbackChainCard
import com.wangzan101.mika.ui.components.FallbackChainItem

/** Backup models for a role: seamless failover if the primary model hits limits or goes down. */
@Composable
internal fun FallbackSection(
    ui: ModelSetupUi,
    role: ModelRole = ModelRole.Main,
    onPickFallback: (ModelRole, Int) -> Unit,
    onClearFallback: (ModelRole, Int) -> Unit,
    onMoveFallback: (ModelRole, Int, Int) -> Unit = { _, _, _ -> },
) {
    val shouldShow = when (role) {
        ModelRole.Main -> ui.roles.main != null
        ModelRole.Vision -> ui.roles.vision is RoleChoice.Model
        ModelRole.Light -> ui.roles.light is RoleChoice.Model
    }
    if (!shouldShow) return

    val roleLabel = ModelsCopy.roleLabel(role)
    fun item(cfg: ProviderConfig?): FallbackChainItem = FallbackChainItem(
        name = cfg?.model ?: ModelsCopy.UNSET,
        detail = cfg?.let { ui.providerOf(it)?.let(ModelsCopy::providerName) ?: it.name }.orEmpty(),
        missing = cfg == null,
    )
    FallbackChainCard(
        title = stringResource(R.string.model_fallback_chain_title, roleLabel),
        primary = ui.configFor(role)?.let(::item),
        fallbacks = ui.roles.fallbacks(role).map { id -> item(ui.configs.firstOrNull { it.id == id }) },
        max = MAX_FALLBACKS,
        tagPrefix = "role-fallback-${role.name.lowercase()}",
        icon = ModelsCopy.FALLBACK_ICON,
        removeBody = { model -> stringResource(R.string.model_fallback_remove_body, model, roleLabel) },
        footer = stringResource(R.string.model_fallback_footer_note, roleLabel),
        onPick = { slot -> onPickFallback(role, slot) },
        onRemove = { slot -> onClearFallback(role, slot) },
        onMove = { from, to -> onMoveFallback(role, from, to) },
    )
}
