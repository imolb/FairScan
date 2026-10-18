/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.fairscan.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.fairscan.app.R
import org.fairscan.app.data.OcrLanguage
import org.fairscan.app.domain.ExportQuality
import org.fairscan.app.ui.Navigation
import org.fairscan.app.ui.components.BackButton
import org.fairscan.app.ui.dummyNavigation
import org.fairscan.app.ui.theme.FairScanTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onDefaultColorModeChanged: (DefaultColorMode) -> Unit,
    actions: SettingsActions,
    onExportFormatChanged: (ExportFormat) -> Unit,
    onExportQualityChanged: (ExportQuality) -> Unit,
    onDefaultFileNameStyleChanged: (DefaultFilenameStyle) -> Unit,
    navigation: Navigation,
) {
    BackHandler { navigation.back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { BackButton(navigation.back) },
            )
        }
    ) { paddingValues ->
        SettingsContent(
            uiState,
            onDefaultColorModeChanged,
            actions,
            onExportFormatChanged,
            onExportQualityChanged,
            onDefaultFileNameStyleChanged,
            navigation,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    onDefaultColorModeChanged: (DefaultColorMode) -> Unit,
    actions: SettingsActions,
    onExportFormatChanged: (ExportFormat) -> Unit,
    onExportQualityChanged: (ExportQuality) -> Unit,
    onDefaultFileNameStyleChanged: (DefaultFilenameStyle) -> Unit,
    navigation: Navigation,
    modifier: Modifier = Modifier,
) {
    val displayLocale = Locale.current.platformLocale
    val export = uiState.export
    var renamingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var addingTagGroup by rememberSaveable { mutableStateOf(false) }
    var addingTagGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    // Encodes the tag awaiting a new synonym as "groupName\u0000tagName"
    var addingSynonymFor by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .padding(vertical = 8.dp, horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        val context = LocalResources.current

        Text(stringResource(R.string.settings_section_scan), style = MaterialTheme.typography.titleLarge)

        SingleChoiceSetting(
            title = stringResource(R.string.color_mode_default),
            entries = DefaultColorMode.entries,
            onValueChanged = onDefaultColorModeChanged,
            label = { t -> context.getString(t.labelResource) },
            selectedValue = uiState.defaultColorMode,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.settings_section_export), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        ExportFoldersSetting(
            folders = export.folders,
            folderNames = export.folderNames,
            folderPaths = export.folderPaths,
            onAddFolderClick = actions.onAddFolderClick,
            onRenameFolder = { uri -> renamingFolder = uri },
            onDeleteFolder = actions.onDeleteFolder,
            onMoveFolder = actions.onMoveFolder,
        )

        Spacer(Modifier.height(16.dp))

        SingleChoiceSetting(
            title = stringResource(R.string.export_quality),
            entries = ExportQuality.entries.reversed(),
            selectedValue = export.quality,
            onValueChanged = onExportQualityChanged,
            label = { t -> context.getString(t.labelResource) },
        )

        SingleChoiceSetting(
            title = stringResource(R.string.export_format),
            entries = ExportFormat.entries,
            selectedValue = export.format,
            onValueChanged = onExportFormatChanged,
            label = { it.name },
        )

        SingleChoiceSetting(
            title = stringResource(R.string.settings_default_filename),
            entries = DefaultFilenameStyle.entries,
            selectedValue = export.filenameStyle,
            onValueChanged = onDefaultFileNameStyleChanged,
            label = { it.filename() },
        )

        Spacer(Modifier.height(16.dp))

        TagsSetting(
            tagGroups = export.tagGroups,
            onAddTagGroup = { addingTagGroup = true },
            onDeleteTagGroup = actions.onDeleteTagGroup,
            onMoveTagGroup = actions.onMoveTagGroup,
            onAddTag = { group -> addingTagGroupName = group },
            onDeleteTag = actions.onDeleteTag,
            onMoveTag = actions.onMoveTag,
            onAddSynonym = { group, tag -> addingSynonymFor = "$group\u0000$tag" },
            onDeleteSynonym = actions.onDeleteSynonym,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.settings_section_ocr),
            style = MaterialTheme.typography.titleLarge
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_ocr_languages)) },
            supportingContent = {
                Text(uiState.enabledOcrLanguages
                    .map { OcrLanguage(it).displayName(displayLocale) }
                    .sorted()
                    .joinToString(" • ")
                    .ifEmpty { stringResource(R.string.settings_ocr_languages_disabled) }
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable { navigation.toOcrLanguagesScreen() }
        )
    }

    // Name of the folder picked with the system folder picker
    export.pendingNewFolderUri?.let { uri ->
        NameInputDialog(
            title = stringResource(R.string.export_folder_name_dialog_title),
            initialName = "",
            onConfirm = { name -> actions.onFolderNameConfirmed(uri, name) },
            onDismiss = { actions.onCancelAddFolder(uri) },
        )
    }

    renamingFolder?.let { uri ->
        val folder = export.folders.firstOrNull { it.uri == uri }
        if (folder != null) {
            NameInputDialog(
                title = stringResource(R.string.export_folder_name_dialog_title),
                initialName = folder.givenName,
                onConfirm = { name ->
                    actions.onRenameFolder(uri, name)
                    renamingFolder = null
                },
                onDismiss = { renamingFolder = null },
            )
        }
    }

    if (addingTagGroup) {
        NameInputDialog(
            title = stringResource(R.string.tag_group_name_dialog_title),
            initialName = "",
            onConfirm = { name ->
                actions.onAddTagGroup(name)
                addingTagGroup = false
            },
            onDismiss = { addingTagGroup = false },
        )
    }

    addingTagGroupName?.let { groupName ->
        val group = export.tagGroups.firstOrNull { it.name == groupName }
        if (group != null) {
            NameInputDialog(
                title = stringResource(R.string.tag_name_dialog_title, groupName),
                initialName = "",
                onConfirm = { tag ->
                    actions.onAddTag(groupName, tag)
                    addingTagGroupName = null
                },
                onDismiss = { addingTagGroupName = null },
            )
        }
    }

    addingSynonymFor?.let { encoded ->
        val parts = encoded.split('\u0000')
        val groupName = parts.getOrNull(0)
        val tagName = parts.getOrNull(1)
        val tag = export.tagGroups
            .firstOrNull { it.name == groupName }
            ?.tags?.firstOrNull { it.name == tagName }
        if (groupName != null && tag != null) {
            NameInputDialog(
                title = stringResource(R.string.synonym_dialog_title, tag.name),
                initialName = "",
                onConfirm = { synonym ->
                    actions.onAddSynonym(groupName, tag.name, synonym)
                    addingSynonymFor = null
                },
                onDismiss = { addingSynonymFor = null },
            )
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ExportFoldersSetting(
    folders: List<ExportFolder>,
    folderNames: Map<String, String?>,
    folderPaths: Map<String, String?>,
    onAddFolderClick: () -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveFolder: (String, Boolean) -> Unit,
) {
    Column {
        Text(stringResource(R.string.export_folders), style = MaterialTheme.typography.bodyLarge)

        folders.forEachIndexed { index, folder ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1: folder icon and given name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        folder.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Row 2: end of the full path on the device
                if (folderNames.containsKey(folder.uri) && folderNames[folder.uri] == null) {
                    Text(
                        stringResource(R.string.export_folder_permission_lost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    folderPaths[folder.uri]?.let { path ->
                        Text(
                            pathTail(path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Row 3: move up/down, rename, delete
                Row {
                    IconButton(
                        onClick = { onMoveFolder(folder.uri, true) },
                        enabled = index > 0,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp,
                            stringResource(R.string.move_up))
                    }
                    IconButton(
                        onClick = { onMoveFolder(folder.uri, false) },
                        enabled = index < folders.size - 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown,
                            stringResource(R.string.move_down))
                    }
                    IconButton(onClick = { onRenameFolder(folder.uri) }) {
                        Icon(Icons.Default.Edit, stringResource(R.string.rename))
                    }
                    IconButton(onClick = { onDeleteFolder(folder.uri) }) {
                        Icon(Icons.Default.Delete,
                            stringResource(R.string.delete_export_folder))
                    }
                }
            }
            if (index < folders.lastIndex) {
                HorizontalDivider()
            }
        }

        TextButton(onClick = onAddFolderClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.add_export_folder))
        }
    }
}

/** Keeps the end of the path visible, with a leading ellipsis if it is long. */
private fun pathTail(path: String, maxChars: Int = 48): String =
    if (path.length > maxChars) "…" + path.takeLast(maxChars - 1) else path

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSetting(
    tagGroups: List<TagGroup>,
    onAddTagGroup: () -> Unit,
    onDeleteTagGroup: (String) -> Unit,
    onMoveTagGroup: (String, Boolean) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTag: (groupName: String, tagName: String) -> Unit,
    onMoveTag: (groupName: String, tagName: String, up: Boolean) -> Unit,
    onAddSynonym: (groupName: String, tagName: String) -> Unit,
    onDeleteSynonym: (groupName: String, tagName: String, synonym: String) -> Unit,
) {
    Column {
        Text(stringResource(R.string.settings_section_tags), style = MaterialTheme.typography.bodyLarge)

        tagGroups.forEachIndexed { index, group ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1: group name
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
                // Row 2: move up/down, add tag, delete group
                Row {
                    IconButton(
                        onClick = { onMoveTagGroup(group.name, true) },
                        enabled = index > 0,
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp,
                            stringResource(R.string.move_up))
                    }
                    IconButton(
                        onClick = { onMoveTagGroup(group.name, false) },
                        enabled = index < tagGroups.size - 1,
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown,
                            stringResource(R.string.move_down))
                    }
                    TextButton(onClick = { onAddTag(group.name) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(R.string.add_tag))
                    }
                    IconButton(onClick = { onDeleteTagGroup(group.name) }) {
                        Icon(Icons.Default.Delete,
                            stringResource(R.string.delete_tag_group))
                    }
                }
                // Tags of the group, each with its synonyms
                group.tags.forEachIndexed { tagIndex, tag ->
                    TagSettingEntry(
                        group, tag, tagIndex, group.tags.size,
                        onMoveTag, onDeleteTag, onAddSynonym, onDeleteSynonym,
                    )
                }
            }
            if (index < tagGroups.lastIndex) {
                HorizontalDivider()
            }
        }

        TextButton(onClick = onAddTagGroup) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.add_tag_group))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSettingEntry(
    group: TagGroup,
    tag: Tag,
    tagIndex: Int,
    tagCount: Int,
    onMoveTag: (groupName: String, tagName: String, up: Boolean) -> Unit,
    onDeleteTag: (groupName: String, tagName: String) -> Unit,
    onAddSynonym: (groupName: String, tagName: String) -> Unit,
    onDeleteSynonym: (groupName: String, tagName: String, synonym: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Tag name with the move up/down, add-synonym and delete buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tag.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            IconButton(
                onClick = { onMoveTag(group.name, tag.name, true) },
                enabled = tagIndex > 0,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up))
            }
            IconButton(
                onClick = { onMoveTag(group.name, tag.name, false) },
                enabled = tagIndex < tagCount - 1,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down))
            }
            IconButton(onClick = { onAddSynonym(group.name, tag.name) }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_synonym))
            }
            IconButton(onClick = { onDeleteTag(group.name, tag.name) }) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete_tag))
            }
        }
        // Synonyms, wrapping to the next line when long/many
        if (tag.synonyms.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                tag.synonyms.forEach { synonym ->
                    InputChip(
                        selected = false,
                        onClick = { onDeleteSynonym(group.name, tag.name, synonym) },
                        label = { Text(synonym) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.delete_synonym),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun <T> SingleChoiceSetting(
    title: String,
    entries: List<T>,
    selectedValue: T,
    onValueChanged: (T) -> Unit,
    label: (T) -> String,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(title)
        },
        supportingContent = {
            Text(label(selectedValue))
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        },
        modifier = Modifier.clickable {
            showDialog = true
        }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = {
                Text(title)
            },
            text = {
                Column {
                    entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChanged(entry)
                                    showDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedValue == entry,
                                onClick = {
                                    onValueChanged(entry)
                                    showDialog = false
                                }
                            )

                            Text(
                                text = label(entry),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }
}

@Preview
@Preview(heightDp = 780)
@Composable
fun SettingsScreenPreviewWithoutDir() {
    SettingsScreenPreview(SettingsUiState(
        installedOcrLanguages = setOf("fra", "eng", "deu"),
        enabledOcrLanguages = setOf("fra", "eng")
    ))
}

@Preview
@Composable
fun SettingsScreenPreviewWithDir() {
    SettingsScreenPreview(
        SettingsUiState(
            export = ExportSettingsUiState(
                folders = listOf(
                    ExportFolder("content://root/dir1", "Rechnungen"),
                    ExportFolder("content://root/dir2", "Privat"),
                ),
                folderNames = mapOf(
                    "content://root/dir1" to "Invoices",
                    "content://root/dir2" to null,
                ),
                folderPaths = mapOf(
                    "content://root/dir1" to "Documents/Scans/Invoices",
                    "content://root/dir2" to "Documents/Scans/Privat",
                ),
                tagGroups = listOf(
                    TagGroup(
                        "Dokumenttyp",
                        listOf(
                            Tag("Rechnung", listOf("Invoice", "Rechnungsnummer")),
                            Tag("Lieferschein"),
                        ),
                    ),
                    TagGroup("Projekt", listOf(Tag("Alpha"), Tag("Beta"))),
                ),
            )
        )
    )
}

@Composable
fun SettingsScreenPreview(uiState: SettingsUiState) {
    FairScanTheme {
        SettingsScreen(
            uiState,
            onDefaultColorModeChanged = {},
            actions = SettingsActions(
                onAddFolderClick = {},
                onFolderNameConfirmed = { _, _ -> },
                onCancelAddFolder = { _ -> },
                onDeleteFolder = {},
                onRenameFolder = { _, _ -> },
                onMoveFolder = { _, _ -> },
                onAddTagGroup = {},
                onDeleteTagGroup = {},
                onMoveTagGroup = { _, _ -> },
                onAddTag = { _, _ -> },
                onDeleteTag = { _, _ -> },
                onMoveTag = { _, _, _ -> },
                onAddSynonym = { _, _, _ -> },
                onDeleteSynonym = { _, _, _ -> },
            ),
            onExportFormatChanged = {},
            onExportQualityChanged = {},
            onDefaultFileNameStyleChanged = {},
            navigation = dummyNavigation(),
        )
    }
}
