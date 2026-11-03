package com.replydev.reveille.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.replydev.reveille.R
import com.replydev.reveille.ui.theme.ReveilleTheme

/** The four top-level destinations, in bar order. [route] is the navigation route. */
enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    Today("home", R.string.nav_today, R.drawable.ic_today),
    Policies("policies", R.string.nav_policies, R.drawable.ic_policies),
    Health("reliability", R.string.nav_health, R.drawable.ic_health),
    Settings("settings", R.string.nav_settings, R.drawable.ic_settings),
    ;

    companion object {
        fun of(route: String?): Tab? = entries.firstOrNull { it.route == route }
    }
}

/** The bar's height above the system navigation bar. Tab screens reserve it with [NavBarSpacer]. */
private val NavBarHeight = 64.dp

/** Takes up exactly the room [ReveilleNavBar] covers, so content and FABs sit above it. */
@Composable
fun NavBarSpacer() {
    Column {
        Spacer(Modifier.height(NavBarHeight))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/**
 * [healthBadge] (a `Danger` dot on Health) wins over [settingsBadge] (an `Accent` dot on Settings for an update): when
 * both apply only Health's dot shows.
 */
@Composable
fun ReveilleNavBar(selected: Tab?, onSelect: (Tab) -> Unit, healthBadge: Boolean, modifier: Modifier = Modifier, settingsBadge: Boolean = false) {
    val colors = ReveilleTheme.colors
    Column(modifier.fillMaxWidth().background(colors.navBg)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.surfaceHigh))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(NavBarHeight - 1.dp).padding(horizontal = 12.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                val badge = when (tab) {
                    Tab.Health -> if (healthBadge) colors.danger else null
                    Tab.Settings -> if (settingsBadge && !healthBadge) colors.accent else null
                    else -> null
                }
                NavItem(tab, tab == selected, badge = badge, onClick = { onSelect(tab) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, badge: Color?, onClick: () -> Unit, modifier: Modifier) {
    val colors = ReveilleTheme.colors
    val badgeState = if (badge == null) null else stringResource(if (tab == Tab.Settings) R.string.nav_update_available else R.string.nav_needs_attention)
    Column(
        modifier
            .fillMaxHeight()
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { if (badgeState != null) stateDescription = badgeState }
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 30.dp).background(if (selected) colors.accentContainer else Color.Transparent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            ReveilleIcon(tab.icon, null, tint = if (selected) colors.accent else colors.muted)
            if (badge != null) Dot(badge, Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 3.dp))
        }
        Text(
            stringResource(tab.label),
            style = ReveilleTheme.type.small.copy(fontWeight = FontWeight.Medium),
            color = if (selected) colors.onBg else colors.muted,
        )
    }
}
