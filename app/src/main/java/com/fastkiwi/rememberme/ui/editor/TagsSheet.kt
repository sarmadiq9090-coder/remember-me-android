package com.fastkiwi.rememberme.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fastkiwi.rememberme.R
import com.fastkiwi.rememberme.core.Organize
import com.fastkiwi.rememberme.ui.ButtonPair
import com.fastkiwi.rememberme.ui.Dimens
import com.fastkiwi.rememberme.ui.Ms
import com.fastkiwi.rememberme.ui.SheetShape

/**
 * «الوسوم» (DESIGN-SPEC §5.5 «التنظيم», F-062, F-063): the reminder's tags as selected chips (a tap removes
 * one), an add field, the user's lists (tags of data/Filters.kt, marked with the list icon) and the other
 * tags in use as chips to add. Windows' rules apply on «تم» (Organize.normalizeTags: 10 tags, 30 characters).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsSheet(tags: List<String>, lists: List<String>, inUse: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(tags) }
    var text by remember { mutableStateOf("") }
    fun has(t: String) = chosen.any { Organize.fold(it) == Organize.fold(t) }
    fun add() {
        val next = Organize.normalizeTags(chosen + text)
        chosen = next
        text = ""
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = Dimens.l)) {
            Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Dimens.xl).semantics { heading() })
            EditorHeader(stringResource(R.string.tags_chosen))
            if (chosen.isEmpty()) {
                Text(stringResource(R.string.tags_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.xl))
            } else {
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    chosen.forEach { t ->
                        FilterChip(
                            selected = true, onClick = { chosen = chosen - t }, label = { Text(t) },
                            leadingIcon = { Icon(Ms.Tag, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Ms.Close, stringResource(R.string.tag_remove, t), Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(Organize.TAG_CHARS) }, label = { Text(stringResource(R.string.tag_new)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    supportingText = { Text(stringResource(R.string.tags_limit)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::add, enabled = text.isNotBlank() && chosen.size < Organize.MAX_TAGS, modifier = Modifier.heightIn(min = Dimens.touch)) {
                    Text(stringResource(R.string.tag_add))
                }
            }
            val listTags = lists.filter { !has(it) }
            if (listTags.isNotEmpty()) {
                EditorHeader(stringResource(R.string.tags_from_lists))
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    listTags.forEach { t ->
                        FilterChip(selected = false, onClick = { chosen = Organize.normalizeTags(chosen + t) }, label = { Text(t) },
                            leadingIcon = { Icon(Ms.ListIcon, null, Modifier.size(18.dp)) })
                    }
                }
            }
            val others = inUse.filter { !has(it) && lists.none { l -> Organize.fold(l) == Organize.fold(it) } }
            if (others.isNotEmpty()) {
                Spacer(Modifier.size(Dimens.s))
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    others.forEach { t ->
                        FilterChip(selected = false, onClick = { chosen = Organize.normalizeTags(chosen + t) }, label = { Text(t) },
                            leadingIcon = { Icon(Ms.Tag, null, Modifier.size(18.dp)) })
                    }
                }
            }
            Spacer(Modifier.size(Dimens.m))
            Column(Modifier.padding(horizontal = Dimens.l)) {
                ButtonPair(
                    LocalDensity.current.fontScale >= 1.5f,
                    secondary = { m -> OutlinedButton(onClick = onDismiss, modifier = m.heightIn(min = Dimens.touch)) { Text(stringResource(R.string.cancel)) } },
                    primary = { m ->
                        Button(onClick = { onDone(Organize.normalizeTags(chosen + text)); onDismiss() }, modifier = m.heightIn(min = Dimens.touch)) {
                            Text(stringResource(R.string.done))
                        }
                    },
                )
            }
        }
    }
}
