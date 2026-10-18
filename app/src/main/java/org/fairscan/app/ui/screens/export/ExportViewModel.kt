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
package org.fairscan.app.ui.screens.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairscan.app.AppContainer
import org.fairscan.app.R
import org.fairscan.app.data.FileManager
import org.fairscan.app.data.ImageRepository
import org.fairscan.app.domain.ExportQuality
import org.fairscan.app.domain.PageViewKey
import org.fairscan.app.domain.jpegsToExport
import org.fairscan.app.domain.pagesToExport
import org.fairscan.app.ui.screens.settings.ExportFormat
import org.fairscan.app.ui.screens.settings.ExportFormat.PDF
import org.fairscan.app.ui.screens.settings.ExportFolder
import org.fairscan.imageprocessing.ColorMode
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Date
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed interface ExportEvent {
    data object RequestSave : ExportEvent
    data class Share(val result: ExportResult) : ExportEvent
}

class ExportViewModel(container: AppContainer, val imageRepository: ImageRepository): ViewModel() {

    private val preparationDir = container.preparationDir
    private val fileManager = container.fileManager
    private val settingsRepository = container.settingsRepository
    private val ocrService = container.ocrService
    private val logger = container.logger

    private val _events = MutableSharedFlow<ExportEvent>()
    val events = _events.asSharedFlow()

    private suspend fun generatePdf(
        exportQuality: ExportQuality,
        disableOcr: Boolean,
        onPageCompleted: (Int) -> Unit,
        onOcrText: (pageIndex: Int, text: String) -> Unit = { _, _ -> },
    ): ExportResult.Pdf = withContext(Dispatchers.IO) {
        val pageToExports = pagesToExport(imageRepository, exportQuality)
        val pdf = fileManager.generatePdf(pageToExports, disableOcr, onPageCompleted, onOcrText)
        return@withContext ExportResult.Pdf(pdf.file, pdf.sizeInBytes, pdf.pageCount)
    }

    suspend fun generatePdfForExternalCall(): ExportResult.Pdf {
        val pdf = generatePdf(ExportQuality.BALANCED, true, {})
        val sourceFile = pdf.file
        val targetFile = File(sourceFile.parentFile, defaultFilename() + ".pdf")
        if (sourceFile.absolutePath == targetFile.absolutePath) return pdf
        if (targetFile.exists() || !sourceFile.renameTo(targetFile)) return pdf
        return pdf.copy(file = targetFile)
    }

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var lastPreparationKey: ExportPreparationKey? = null
    private var preparationJob: Job? = null

    fun setFilename(name: String) {
        _uiState.update {
            it.copy(filename = name, isUserDefinedFilename = true)
        }
    }

    fun resetFilename() {
        // A cleared field is not a user edit: the next scan must get a fresh proposal
        _uiState.update {
            it.copy(filename = "", isUserDefinedFilename = false)
        }
    }

    private fun defaultFilename(): String {
        return settingsRepository.defaultFilenameStyle.value.filename()
    }

    private fun ensureValidFilename() {
        _uiState.update {
            val normalized = it.filename.trim()
            if (it.isUserDefinedFilename && normalized.isNotEmpty()) {
                // Keep the user's filename, only normalize whitespace
                if (normalized != it.filename) it.copy(filename = normalized) else it
            } else {
                // Keep the generated filename untouched; it is recomputed below
                it
            }
        }
        updateFilenameProposal()
    }

    private suspend fun currentPageKeys(): ImmutableList<PageViewKey> =
        imageRepository.pages().map { it.key() }.toImmutableList()

    fun cancelPreparationJob() {
        preparationJob?.let {
            // keep result if job is not active
            if (it.isActive) {
                _uiState.update { ExportUiState() }
            }
            it.cancel()
        }
    }

    fun prepareExportIfNeeded() {
        ensureValidFilename()

        viewModelScope.launch {
            val exportQuality = settingsRepository.exportQuality.first()
            val exportFormat = settingsRepository.exportFormat.first()
            val ocrLanguageString = ocrService.languageString()
            val exportFolders = settingsRepository.exportFolders.first()
            val tagGroups = settingsRepository.tagGroups.first()

            val currentPageKeys = currentPageKeys()
            val key = ExportPreparationKey(
                currentPageKeys, exportFormat, exportQuality, ocrLanguageString)
            if (key == lastPreparationKey && _uiState.value.result != null) {
                return@launch
            }
            val pageCount = currentPageKeys.size

            lastPreparationKey = key
            preparationJob?.cancel()
            preparationJob?.join()
            hasUserSelectedTags = false
            hasUserSelectedFolder = false

            preparationJob = launch {
                val ocrActivation = if (exportFormat == PDF) ocrLanguageString.isNotEmpty() else null
                val blackAndWhiteAsJpeg = exportFormat == ExportFormat.JPEG &&
                    currentPageKeys.any { it.colorMode == ColorMode.BLACK_AND_WHITE }
                _uiState.update {
                    val selectedFolderUri =
                        it.selectedFolderUri?.takeIf { selected -> exportFolders.any { it.uri == selected } }
                    val selectedTags = it.selectedTags.filterKeys { groupName ->
                        tagGroups.any { it.name == groupName }
                    }
                    ExportUiState(
                        filename = it.filename,
                        isUserDefinedFilename = it.isUserDefinedFilename,
                        format = exportFormat,
                        isGenerating = true,
                        progress = ExportProgress(0, pageCount),
                        ocrActivation = ocrActivation,
                        blackAndWhiteAsJpeg = blackAndWhiteAsJpeg,
                        exportFolders = exportFolders,
                        selectedFolderUri = selectedFolderUri,
                        tagGroups = tagGroups,
                        selectedTags = selectedTags,
                    )
                }
                if (ocrActivation != true) {
                    updateFilenameProposal()
                }
                val onPageCompleted: (Int) -> Unit = { completedPages ->
                    ensureActive()
                    _uiState.update {
                        it.copy(progress = ExportProgress(completedPages, pageCount))
                    }
                }
                val ocrPageTexts = mutableListOf<String>()
                val onOcrText: (Int, String) -> Unit = { _, text ->
                    ensureActive()
                    ocrPageTexts.add(text)
                }
                try {
                    val t1 = System.currentTimeMillis()
                    val result = if (exportFormat == ExportFormat.JPEG) {
                        generateJpegs(exportQuality, onPageCompleted)
                    } else {
                        generatePdf(exportQuality, false, onPageCompleted, onOcrText)
                    }
                    _uiState.update { it.copy(result = result) }
                    if (ocrActivation == true) {
                        // Preselect the tags and the export folder whose given
                        // name appears first in the scanned text, as long as the
                        // user has not made a manual selection
                        val preselectedTags =
                            if (!hasUserSelectedTags) {
                                TagPreselection.preselect(tagGroups, ocrPageTexts)
                            } else {
                                null
                            }
                        val preselectedFolderUri =
                            if (!hasUserSelectedFolder) {
                                TagPreselection
                                    .firstMatchIndex(
                                        exportFolders.map { listOf(it.givenName) },
                                        ocrPageTexts,
                                    )
                                    ?.let { matchIndex -> exportFolders.getOrNull(matchIndex)?.uri }
                            } else {
                                null
                            }
                        if (preselectedTags != null || preselectedFolderUri != null) {
                            _uiState.update { state ->
                                state.copy(
                                    selectedTags = preselectedTags ?: state.selectedTags,
                                    selectedFolderUri =
                                        preselectedFolderUri ?: state.selectedFolderUri,
                                )
                            }
                        }
                    }
                    updateFilenameProposal()
                    val t2 = System.currentTimeMillis()
                    Log.i("Export", "Generation: $pageCount pages, $exportQuality, ${t2 - t1} ms")
                } catch (e: CancellationException) {
                    // Preparation cancelled: do nothing
                    throw e
                } catch (e: Exception) {
                    val message = "Failed to prepare $exportFormat export"
                    logger.e("FairScan", message, e)
                    _uiState.update {
                        it.copy(error = ExportError.OnPrepareOrShare(message, e))
                    }
                } finally {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    private var hasUserSelectedTags = false
    private var hasUserSelectedFolder = false

    fun onFolderSelected(uri: String?) {
        hasUserSelectedFolder = true
        _uiState.update {
            it.copy(selectedFolderUri = uri?.takeIf { selected ->
                it.exportFolders.any { folder -> folder.uri == selected }
            })
        }
        updateFilenameProposal()
    }

    fun onTagToggled(groupName: String, tag: String) {
        hasUserSelectedTags = true
        _uiState.update {
            val selected = it.selectedTags[groupName]
            val newTags = if (selected == tag) {
                it.selectedTags - groupName
            } else {
                it.selectedTags + (groupName to tag)
            }
            it.copy(selectedTags = newTags)
        }
        updateFilenameProposal()
    }

    private fun selectedFolder(): ExportFolder? {
        val state = _uiState.value
        return state.exportFolders.firstOrNull { it.uri == state.selectedFolderUri }
    }

    /**
     * Proposes the filename from the current folder and tag selections, unless
     * the user has entered a non-empty filename manually. Falls back to the
     * default filename style when neither a folder nor a tag is selected.
     */
    private fun updateFilenameProposal() {
        _uiState.update { state ->
            if (state.isUserDefinedFilename && state.filename.isNotBlank()) {
                state
            } else {
                val folder = selectedFolder()
                val filename = if (folder == null && state.selectedTags.isEmpty()) {
                    defaultFilename()
                } else {
                    TaggedFilename.proposal(
                        date = Date(),
                        tagGroups = state.tagGroups,
                        selectedTags = state.selectedTags,
                        exportFolderName = folder?.givenName,
                    )
                }
                // A regenerated filename is not user-defined: later selection
                // changes must keep updating it
                state.copy(filename = filename, isUserDefinedFilename = false)
            }
        }
    }

    private suspend fun generateJpegs(
        exportQuality: ExportQuality,
        onPageCompleted: (Int) -> Unit,
    ): ExportResult.Jpeg = withContext(Dispatchers.IO) {
        val jpegPages = jpegsToExport(imageRepository, exportQuality)
        val timestamp = System.currentTimeMillis()
        preparationDir.mkdirs()
        val files = jpegPages.mapIndexed { index, page ->
            val file = File(preparationDir, "$timestamp-${index + 1}.jpg")
            file.writeBytes(page.image.get().bytes)
            onPageCompleted(index + 1)
            file
        }.toList()
        val sizeInBytes = files.sumOf { it.length() }
        ExportResult.Jpeg(files, sizeInBytes)
    }

    private fun renameFile(source: File, target: File) {
        if (source.absolutePath == target.absolutePath) return
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot delete existing file ${target.absolutePath}")
        }
        if (!source.renameTo(target)) {
            throw IOException("Failed to rename ${source.name} to ${target.name}")
        }
    }

    private fun applyRenaming(): ExportResult {
        val result = _uiState.value.result
            ?: throw IllegalStateException("Export result missing")
        ensureValidFilename()
        val filename = _uiState.value.filename
        val updated = when (result) {
            is ExportResult.Pdf -> {
                val fileName = FileManager.addPdfExtensionIfMissing(filename)
                val newFile = File(result.file.parentFile, fileName)
                renameFile(result.file, newFile)
                ExportResult.Pdf(newFile, result.sizeInBytes, result.pageCount)
            }
            is ExportResult.Jpeg -> {
                val base = filename.removeSuffix(".jpg")
                val files = result.files
                val renamedFiles = files.mapIndexed { index, file ->
                    val indexSuffix = if (files.size == 1) "" else "_${index + 1}"
                    val newFile = File(file.parentFile, "${base}${indexSuffix}.jpg")
                    renameFile(file, newFile)
                    newFile
                }
                result.copy(jpegFiles = renamedFiles)
            }
        }
        _uiState.update { it.copy(result = updated) }
        return updated
    }

    fun onShareClicked() {
        viewModelScope.launch {
            try {
                val result = applyRenaming()
                _events.emit(ExportEvent.Share(result))
                _uiState.update { it.copy(hasShared = true) }
            } catch (e: Exception) {
                val message = "Failed to prepare share"
                logger.e("FairScan", message, e)
                _uiState.update { it.copy(error = ExportError.OnPrepareOrShare(message, e)) }
            }
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _events.emit(ExportEvent.RequestSave)
        }
    }

    fun onRequestSave(context: Context) {
        viewModelScope.launch {
            _uiState.update {it.copy(isSaving = true, error = null, savedBundle = null) }
            val exportFormat = uiState.value.format
            val saveDir = saveDir(context)
            if (saveDir == null && uiState.value.exportFolders.isNotEmpty()) {
                // The UI disables Save in this case; this is a safety net
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = ExportError.OnSave(
                            R.string.error_export_folder_required, null)
                    )
                }
                return@launch
            }
            try {
                // Must not run on the main thread: some SAF providers (e.g. Nextcloud)
                // may perform network I/O
                withContext(Dispatchers.IO) {
                    save(context, saveDir, exportFormat)
                }
            } catch (e: MissingExportDirPermissionException) {
                logger.e("FairScan", "Missing export dir permission", e)
                _uiState.update {
                    it.copy(error =
                        ExportError.OnSave(R.string.error_export_dir_permission_lost, saveDir))
                }
            } catch (e: Exception) {
                logger.e("FairScan", "Failed to save PDF", e)
                _uiState.update {
                    it.copy(error = ExportError.OnSave(R.string.error_save, saveDir, e))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun saveDir(context: Context): SaveDir? {
        val state = _uiState.value
        // With configured export folders, saving goes to the selected one.
        // Without configured folders, it goes to Downloads (saveDir == null).
        val uri = state.selectedFolderUri?.toUri() ?: return null
        val name = resolveExportDirName(context, uri)
        return SaveDir(uri, name)
    }

    private suspend fun save(context: Context, saveDir: SaveDir?, exportFormat: ExportFormat) {
        val result = applyRenaming()
        val savedItems = mutableListOf<SavedItem>()
        val filesForMediaScan = mutableListOf<File>()

        for (file in result.files) {
            val saved = if (saveDir == null) {
                // No export dir defined -> save to Downloads
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: use MediaStore API
                    val uri = saveViaMediaStore(context, file, exportFormat)
                    SavedItem(uri, file.name, exportFormat)
                } else {
                    // Android 8 and 9: use File API
                    // (MediaStore doesn't allow to choose Downloads for Android<10)
                    val out = fileManager.copyToExternalDir(file)
                    filesForMediaScan.add(out)
                    SavedItem(out.toUri(), out.name, exportFormat)
                }
            } else {
                // Use Storage Access Framework to save to the chosen directory
                if (!context.contentResolver.persistedUriPermissions.any { perm ->
                        perm.uri == saveDir.uri && perm.isWritePermission
                    }) {
                    throw MissingExportDirPermissionException(saveDir.uri)
                }
                val safFile = saveViaSaf(context, file, saveDir.uri, exportFormat)
                SavedItem(safFile.uri, safFile.name ?: file.name, exportFormat)
            }
            savedItems += saved
        }

        val bundle = SavedBundle(savedItems, saveDir)
        _uiState.update { it.copy(savedBundle = bundle) }

        filesForMediaScan.forEach { f -> mediaScan(context, f, exportFormat.mimeType) }
    }

    private suspend fun mediaScan(
        context: Context,
        file: File,
        mimeType: String
    ): Uri? = suspendCoroutine { cont ->
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType)
        ) { _, uri ->
            cont.resume(uri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        format: ExportFormat
    ): Uri {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: throw IOException("Failed to open output stream")

        return uri
    }

    private fun saveViaSaf(
        context: Context,
        source: File,
        exportDirUri: Uri,
        exportFormat: ExportFormat,
    ): DocumentFile {
        val resolver = context.contentResolver

        val tree = DocumentFile.fromTreeUri(context, exportDirUri)
            ?: throw IllegalStateException("Invalid SAF directory")

        // Name collisions are handled automatically by SAF provider
        val target = tree.createFile(exportFormat.mimeType, source.name)
            ?: throw IllegalStateException("Unable to create SAF file")

        resolver.openOutputStream(target.uri)?.use { output ->
            FileInputStream(source).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Failed to open SAF output stream")

        return target
    }

    fun cleanUpOldPreparedFiles(thresholdInMillis: Int) {
        fileManager.cleanUpOldFiles(thresholdInMillis)
    }

    private fun resolveExportDirName(context: Context, exportDirUri: Uri?): String? {
        return if (exportDirUri == null) {
            null
        } else {
            DocumentFile.fromTreeUri(context, exportDirUri)?.name
        }
    }
}

data class ExportPreparationKey(
    val pages: ImmutableList<PageViewKey>,
    val format: ExportFormat,
    val quality: ExportQuality,
    val ocrLanguageString: String,
)

sealed class ExportResult {
    abstract val files: List<File>
    abstract val sizeInBytes: Long
    abstract val pageCount: Int
    abstract val format: ExportFormat

    data class Pdf(
        val file: File,
        override val sizeInBytes: Long,
        override val pageCount: Int,
    ) : ExportResult() {
        override val files get() = listOf(file)
        override val format: ExportFormat = PDF
    }

    data class Jpeg(
        val jpegFiles: List<File>,
        override val sizeInBytes: Long,
    ) : ExportResult() {
        override val files get() = jpegFiles
        override val pageCount get() = jpegFiles.size
        override val format: ExportFormat = ExportFormat.JPEG
    }
}

data class ExportActions(
    val prepareExportIfNeeded: () -> Unit,
    val setFilename: (String) -> Unit,
    val selectFolder: (String?) -> Unit,
    val toggleTag: (groupName: String, tag: String) -> Unit,
    val share: () -> Unit,
    val save: () -> Unit,
    val open: (SavedItem) -> Unit,
    val cancelPreparationJob: () -> Unit,
)

class MissingExportDirPermissionException(
    val uri: Uri
) : IllegalStateException(
    "Missing persisted write permission for export dir: $uri"
)
