package com.fastkiwi.rememberme.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.TooltipIconButton

/** The ways Home shows the reminders (DESIGN-SPEC §5.1, §5.16). Home always opens in [LIST]; Back from a view returns to it. */
enum class HomeView { LIST, AGENDA, DAY, WEEK, MONTH }

/** The view's name: «القائمة»، «أجندة»، «يوم»، «أسبوع»، «شهر». */
@Composable
fun homeViewName(v: HomeView): String = stringResource(when (v) {
    HomeView.LIST -> R.string.home_view_list
    HomeView.AGENDA -> R.string.home_view_agenda
    HomeView.DAY -> R.string.home_view_day
    HomeView.WEEK -> R.string.home_view_week
    HomeView.MONTH -> R.string.home_view_month
})

/** «طريقة العرض»: an icon button that opens a menu of the five views as radio items. */
@Composable
fun ViewMenuButton(current: HomeView, onSelect: (HomeView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Ms.CalendarViewMonth, stringResource(R.string.home_view_menu), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HomeView.entries.forEach { v ->
                DropdownMenuItem(
                    text = { Text(homeViewName(v)) },
                    leadingIcon = { RadioButton(selected = v == current, onClick = null) },
                    onClick = { open = false; onSelect(v) },
                    modifier = Modifier.semantics { role = Role.RadioButton; selected = v == current },
                )
            }
        }
    }
}
