# セクション5b: セグメント管理フロー（削除・エクスポート・ストレージ）

## 1. セグメント削除フロー

### 1.1 削除対象セグメントの選択

#### PlayerView での UI（スワイプ削除）

```kotlin
@Composable
fun SegmentListView(
    segments: List<VideoSegment>,
    onDeleteSegment: (VideoSegment) -> Unit,
    onSelectSegment: (VideoSegment) -> Unit
) {
    LazyColumn {
        items(
            items = segments.sortedBy { it.order },
            key = { it.id }
        ) { segment ->
            // ✅ スワイプで削除可能なアイテム
            SwipeToDismissItem(
                segment = segment,
                onDelete = { onDeleteSegment(segment) },
                onSelect = { onSelectSegment(segment) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDismissItem(
    segment: VideoSegment,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dismissState = rememberDismissState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == DismissValue.DismissedToStart) {
                // 左にスワイプ → 削除確認ダイアログ表示
                showDeleteDialog = true
                false  // 自動的に元に戻す
            } else {
                false
            }
        }
    )

    SwipeToDismiss(
        state = dismissState,
        directions = setOf(DismissDirection.EndToStart),
        background = {
            // 削除背景（赤）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White
                )
            }
        },
        dismissContent = {
            // セグメントアイテム
            SegmentItem(
                segment = segment,
                onClick = onSelect
            )
        }
    )

    // 削除確認ダイアログ
    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            segmentOrder = segment.order + 1,
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            onDismiss = {
                showDeleteDialog = false
            }
        )
    }
}

@Composable
fun SegmentItem(
    segment: VideoSegment,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${segment.order + 1}",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = segment.uri,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${segment.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}
```

#### 削除確認ダイアログ

```kotlin
@Composable
fun DeleteConfirmationDialog(
    segmentOrder: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("セグメントを削除")
        },
        text = {
            Text("セグメント $segmentOrder を削除しますか？この操作は取り消せません。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                )
            ) {
                Text("削除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift)
.onDelete { indexSet in
    if let index = indexSet.first {
        let segment = sortedSegments[index]
        showDeleteConfirmation(for: segment)
    }
}
```

---

### 1.2 ファイルシステムからの削除

#### ファイルの削除処理

```kotlin
class SegmentFileManager(
    private val context: Context
) {
    private val filesDir = context.filesDir

    /**
     * セグメントファイルを削除
     *
     * @param segment 削除するセグメント
     * @return 削除成功時 true
     */
    fun deleteSegmentFile(segment: VideoSegment): Boolean {
        val file = File(filesDir, segment.uri)

        return try {
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: ${segment.uri}")
                return true  // ファイルがなければ成功とみなす
            }

            val deleted = file.delete()

            if (deleted) {
                Log.d(TAG, "File deleted successfully: ${segment.uri}")
            } else {
                Log.e(TAG, "Failed to delete file: ${segment.uri}")
            }

            deleted
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while deleting file", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while deleting file", e)
            false
        }
    }

    /**
     * 複数のセグメントファイルを一括削除
     */
    fun deleteMultipleSegmentFiles(segments: List<VideoSegment>): Int {
        var successCount = 0

        segments.forEach { segment ->
            if (deleteSegmentFile(segment)) {
                successCount++
            }
        }

        Log.d(TAG, "Deleted $successCount/${segments.size} files")
        return successCount
    }

    /**
     * プロジェクト全体のファイルを削除
     */
    fun deleteProjectFiles(project: Project): Boolean {
        val totalSegments = project.segments.size
        val deletedCount = deleteMultipleSegmentFiles(project.segments)

        return deletedCount == totalSegments
    }

    companion object {
        private const val TAG = "SegmentFileManager"
    }
}
```

#### 削除エラーのハンドリング

```kotlin
sealed class DeleteResult {
    object Success : DeleteResult()
    data class FileNotFound(val fileName: String) : DeleteResult()
    data class PermissionDenied(val fileName: String) : DeleteResult()
    data class IOError(val fileName: String, val message: String) : DeleteResult()
}

class SegmentFileManager(context: Context) {

    fun deleteSegmentFileWithResult(segment: VideoSegment): DeleteResult {
        val file = File(filesDir, segment.uri)

        if (!file.exists()) {
            Log.w(TAG, "File not found: ${segment.uri}")
            return DeleteResult.FileNotFound(segment.uri)
        }

        if (!file.canWrite()) {
            Log.e(TAG, "No write permission: ${segment.uri}")
            return DeleteResult.PermissionDenied(segment.uri)
        }

        return try {
            if (file.delete()) {
                Log.d(TAG, "File deleted: ${segment.uri}")
                DeleteResult.Success
            } else {
                Log.e(TAG, "Delete failed: ${segment.uri}")
                DeleteResult.IOError(segment.uri, "Unknown deletion error")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception", e)
            DeleteResult.PermissionDenied(segment.uri)
        } catch (e: IOException) {
            Log.e(TAG, "IO exception", e)
            DeleteResult.IOError(segment.uri, e.message ?: "IO error")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            DeleteResult.IOError(segment.uri, e.message ?: "Unknown error")
        }
    }
}
```

---

### 1.3 Project データモデルからの削除

#### segments リストから削除

```kotlin
class PlayerViewModel(
    private val repository: ProjectRepository,
    private val fileManager: SegmentFileManager
) : ViewModel() {

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    fun deleteSegment(segment: VideoSegment) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Deleting

            try {
                val currentProject = _project.value ?: throw Exception("No project loaded")

                // 1. ファイルを削除
                val fileResult = fileManager.deleteSegmentFileWithResult(segment)

                when (fileResult) {
                    is DeleteResult.Success -> {
                        Log.d(TAG, "File deleted successfully")
                    }
                    is DeleteResult.FileNotFound -> {
                        Log.w(TAG, "File not found, continuing with data deletion")
                    }
                    is DeleteResult.PermissionDenied -> {
                        throw SecurityException("Permission denied for ${fileResult.fileName}")
                    }
                    is DeleteResult.IOError -> {
                        throw IOException("IO error: ${fileResult.message}")
                    }
                }

                // 2. segments リストから削除
                val updatedSegments = currentProject.segments
                    .filter { it.id != segment.id }

                // 3. order を再計算
                val reorderedSegments = updatedSegments
                    .sortedBy { it.order }
                    .mapIndexed { index, seg ->
                        seg.copy(order = index)
                    }

                // 4. Project を更新
                val updatedProject = currentProject.copy(
                    segments = reorderedSegments,
                    lastModified = System.currentTimeMillis()
                )

                // 5. Repository に保存
                repository.updateProject(updatedProject)

                // 6. 状態を更新
                _project.value = updatedProject

                _deleteState.value = DeleteState.Success

                Log.d(TAG, "Segment deleted: ${segment.id}")
                Log.d(TAG, "Remaining segments: ${reorderedSegments.size}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete segment", e)
                _deleteState.value = DeleteState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearDeleteState() {
        _deleteState.value = DeleteState.Idle
    }
}

sealed class DeleteState {
    object Idle : DeleteState()
    object Deleting : DeleteState()
    object Success : DeleteState()
    data class Error(val message: String) : DeleteState()
}
```

#### order の再計算

```kotlin
fun reorderSegments(segments: List<VideoSegment>): List<VideoSegment> {
    return segments
        .sortedBy { it.order }
        .mapIndexed { index, segment ->
            segment.copy(order = index)
        }
}

// 使用例
val originalSegments = listOf(
    VideoSegment("1", "seg1.mp4", order = 0, durationMs = 1000),
    VideoSegment("2", "seg2.mp4", order = 1, durationMs = 1000),
    VideoSegment("3", "seg3.mp4", order = 2, durationMs = 1000),
    VideoSegment("4", "seg4.mp4", order = 3, durationMs = 1000)
)

// セグメント2を削除
val afterDeletion = originalSegments.filter { it.id != "2" }
// order: [0, 2, 3] → 不連続！

// 再計算
val reordered = reorderSegments(afterDeletion)
// order: [0, 1, 2] → 連続！
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift)
func deleteSegment(from project: Project, segment: VideoSegment) {
    // ファイル削除
    deleteSegmentFile(segment: segment)

    // リストから削除
    var updatedProject = project
    updatedProject.segments.removeAll { $0.id == segment.id }

    // order 再計算
    for (index, _) in updatedProject.segments.enumerated() {
        updatedProject.segments[index].order = index
    }

    updateProject(updatedProject)
}
```

---

## 2. エクスポートフロー

### 2.1 エクスポート準備

#### Composition から MP4 ファイルを生成

```kotlin
class VideoExporter(
    private val context: Context
) {
    private val cacheDir = context.cacheDir

    /**
     * エクスポート品質
     */
    enum class ExportQuality {
        HIGH,    // 元の品質を維持
        MEDIUM,  // 720p
        LOW      // 480p
    }

    /**
     * Composition を MP4 ファイルにエクスポート
     */
    suspend fun exportComposition(
        compositionUri: Uri,
        quality: ExportQuality = ExportQuality.HIGH,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {

        val timestamp = System.currentTimeMillis()
        val outputFile = File(cacheDir, "export_${timestamp}.mp4")

        try {
            // ✅ 品質に応じた設定
            val transformerBuilder = Transformer.Builder(context)

            when (quality) {
                ExportQuality.HIGH -> {
                    // 元の品質を維持
                }
                ExportQuality.MEDIUM -> {
                    transformerBuilder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    // 720p に制限
                }
                ExportQuality.LOW -> {
                    transformerBuilder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    // 480p に制限
                }
            }

            val transformer = transformerBuilder.build()

            // MediaItem を作成
            val mediaItem = MediaItem.fromUri(compositionUri)
            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

            val composition = Composition.Builder(Composition.HDR_MODE_KEEP_HDR)
                .addSequence(
                    Composition.Sequence.Builder()
                        .addMediaItem(editedMediaItem)
                        .build()
                )
                .build()

            // エクスポート実行
            suspendCancellableCoroutine<Unit> { continuation ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(comp: Composition, result: ExportResult) {
                        Log.d(TAG, "Export completed: ${outputFile.name}")
                        continuation.resume(Unit) {}
                    }

                    override fun onError(
                        comp: Composition,
                        result: ExportResult,
                        exception: ExportException
                    ) {
                        Log.e(TAG, "Export failed", exception)
                        continuation.resumeWithException(exception)
                    }
                })

                transformer.start(composition, outputFile.path)
            }

            onProgress(1.0f)
            outputFile

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            outputFile.delete()
            null
        }
    }

    companion object {
        private const val TAG = "VideoExporter"
    }
}
```

#### エクスポート品質の選択

```kotlin
@Composable
fun ExportQualitySelector(
    selectedQuality: VideoExporter.ExportQuality,
    onQualitySelected: (VideoExporter.ExportQuality) -> Unit
) {
    Column {
        Text("エクスポート品質", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(8.dp))

        VideoExporter.ExportQuality.values().forEach { quality ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQualitySelected(quality) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedQuality == quality,
                    onClick = { onQualitySelected(quality) }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column {
                    Text(
                        text = when (quality) {
                            VideoExporter.ExportQuality.HIGH -> "高品質"
                            VideoExporter.ExportQuality.MEDIUM -> "中品質"
                            VideoExporter.ExportQuality.LOW -> "低品質"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = when (quality) {
                            VideoExporter.ExportQuality.HIGH -> "元の解像度を維持（ファイルサイズ大）"
                            VideoExporter.ExportQuality.MEDIUM -> "720p（バランス重視）"
                            VideoExporter.ExportQuality.LOW -> "480p（ファイルサイズ小）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}
```

---

### 2.2 写真ライブラリへの保存

#### MediaStore への保存（Android 10+）

```kotlin
class MediaStoreExporter(
    private val context: Context
) {
    /**
     * 動画ファイルを MediaStore（ギャラリー）に保存
     */
    suspend fun saveToGallery(
        sourceFile: File,
        displayName: String = "ClipFlow_${System.currentTimeMillis()}.mp4"
    ): Uri? = withContext(Dispatchers.IO) {

        val resolver = context.contentResolver

        // ✅ ContentValues を設定
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClipFlow")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        try {
            // ✅ URI を作成
            val uri = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to create MediaStore entry")

            // ✅ ファイルをコピー
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to open output stream")

            // ✅ IS_PENDING を解除
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "Saved to gallery: $uri")
            uri

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
            null
        }
    }

    companion object {
        private const val TAG = "MediaStoreExporter"
    }
}
```

#### 権限の確認と要求

```kotlin
@Composable
fun ExportScreen(
    viewModel: ExportViewModel = viewModel()
) {
    val context = LocalContext.current

    // ✅ 権限の状態
    val writePermissionState = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        // Android 10+ は WRITE_EXTERNAL_STORAGE 不要
        null
    }

    var showPermissionDialog by remember { mutableStateOf(false) }

    fun checkAndExport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ は権限不要
            viewModel.startExport()
        } else {
            // Android 9 以下は権限が必要
            if (writePermissionState?.status?.isGranted == true) {
                viewModel.startExport()
            } else {
                showPermissionDialog = true
            }
        }
    }

    // UI
    Column {
        Button(onClick = { checkAndExport() }) {
            Text("ギャラリーに保存")
        }
    }

    // 権限要求ダイアログ
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("ストレージ権限が必要です") },
            text = { Text("動画をギャラリーに保存するには、ストレージへの書き込み権限が必要です。") },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        writePermissionState?.launchPermissionRequest()
                    }
                ) {
                    Text("権限を付与")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
```

#### 保存完了通知

```kotlin
class ExportViewModel : ViewModel() {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val exporter = VideoExporter(context)
    private val mediaStoreExporter = MediaStoreExporter(context)

    fun exportToGallery(compositionUri: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting(0f)

            try {
                // 1. MP4 ファイルを生成
                val exportedFile = exporter.exportComposition(
                    compositionUri = compositionUri,
                    onProgress = { progress ->
                        _exportState.value = ExportState.Exporting(progress * 0.8f)
                    }
                ) ?: throw Exception("Export failed")

                // 2. ギャラリーに保存
                val galleryUri = mediaStoreExporter.saveToGallery(exportedFile)

                // 3. 一時ファイルを削除
                exportedFile.delete()

                if (galleryUri != null) {
                    _exportState.value = ExportState.Success(galleryUri)
                    Log.d(TAG, "Export completed: $galleryUri")
                } else {
                    throw Exception("Failed to save to gallery")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                _exportState.value = ExportState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed class ExportState {
    object Idle : ExportState()
    data class Exporting(val progress: Float) : ExportState()
    data class Success(val uri: Uri) : ExportState()
    data class Error(val message: String) : ExportState()
}

// UI での表示
@Composable
fun ExportStatusView(exportState: ExportState) {
    when (exportState) {
        is ExportState.Idle -> {
            Text("準備完了")
        }
        is ExportState.Exporting -> {
            Column {
                LinearProgressIndicator(progress = exportState.progress)
                Text("エクスポート中... ${(exportState.progress * 100).toInt()}%")
            }
        }
        is ExportState.Success -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Green.copy(alpha = 0.2f)
                )
            ) {
                Text("✅ ギャラリーに保存されました")
            }
        }
        is ExportState.Error -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Red.copy(alpha = 0.2f)
                )
            ) {
                Text("❌ エラー: ${exportState.message}")
            }
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (PlayerView.swift)
PHPhotoLibrary.requestAuthorization { status in
    if status == .authorized {
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: outputURL)
        }) { success, error in
            if success {
                showExportSuccessAlert = true
            }
        }
    }
}
```

---

### 2.3 エクスポート失敗時の処理

#### ストレージ不足の検出

```kotlin
class StorageChecker(private val context: Context) {

    fun checkAvailableSpace(): StorageStatus {
        val filesDir = context.filesDir
        val statFs = StatFs(filesDir.absolutePath)

        val availableBytes = statFs.availableBytes
        val availableMB = availableBytes / 1024 / 1024

        return when {
            availableMB < 50 -> StorageStatus.Critical(availableMB)
            availableMB < 200 -> StorageStatus.Low(availableMB)
            else -> StorageStatus.OK(availableMB)
        }
    }

    fun hasEnoughSpaceForExport(estimatedSizeMB: Long): Boolean {
        val status = checkAvailableSpace()
        return when (status) {
            is StorageStatus.OK -> true
            is StorageStatus.Low -> status.availableMB > estimatedSizeMB * 2
            is StorageStatus.Critical -> false
        }
    }
}

sealed class StorageStatus {
    data class OK(val availableMB: Long) : StorageStatus()
    data class Low(val availableMB: Long) : StorageStatus()
    data class Critical(val availableMB: Long) : StorageStatus()
}
```

#### 権限拒否への対応

```kotlin
@Composable
fun handlePermissionDenied(
    context: Context,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("権限が拒否されました") },
        text = {
            Text("ストレージ権限がないため、動画をギャラリーに保存できません。設定から権限を許可してください。")
        },
        confirmButton = {
            Button(onClick = {
                // 設定画面を開く
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
                onOpenSettings()
            }) {
                Text("設定を開く")
            }
        },
        dismissButton = {
            TextButton(onClick = { }) {
                Text("キャンセル")
            }
        }
    )
}
```

#### ユーザーへのエラー通知

```kotlin
@Composable
fun ExportErrorHandler(
    error: ExportState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val errorMessage = when {
        error.message.contains("storage", ignoreCase = true) ||
        error.message.contains("space", ignoreCase = true) -> {
            "ストレージ容量が不足しています。不要なファイルを削除してから再試行してください。"
        }
        error.message.contains("permission", ignoreCase = true) -> {
            "ストレージへのアクセス権限がありません。設定から権限を許可してください。"
        }
        error.message.contains("codec", ignoreCase = true) -> {
            "動画のエンコードに失敗しました。別の品質設定で再試行してください。"
        }
        else -> {
            "エクスポートに失敗しました: ${error.message}"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("エクスポートエラー") },
        text = { Text(errorMessage) },
        confirmButton = {
            Button(onClick = onRetry) {
                Text("再試行")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}
```

---

## 3. ファイルシステム管理（詳細）

### 3.1 ストレージパス

#### アプリ専用ディレクトリ

```kotlin
// 内部ストレージ（推奨）
val filesDir = context.filesDir
// パス: /data/data/com.example.clipflow/files/
// 特徴: アプリ専用、root不要、アンインストール時に削除

// キャッシュディレクトリ
val cacheDir = context.cacheDir
// パス: /data/data/com.example.clipflow/cache/
// 特徴: システムが空き容量不足時に自動削除の可能性あり

// 外部ストレージ（アプリ専用）
val externalFilesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
// パス: /storage/emulated/0/Android/data/com.example.clipflow/files/Movies/
// 特徴: より大きな容量、アンインストール時に削除
```

#### 一時ファイルの管理

```kotlin
class TempFileManager(private val context: Context) {
    private val tempFiles = mutableListOf<File>()

    fun createTempFile(prefix: String, suffix: String = ".mp4"): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "${prefix}_${timestamp}$suffix"
        val file = File(context.cacheDir, fileName)

        tempFiles.add(file)
        Log.d(TAG, "Created temp file: ${file.name}")

        return file
    }

    fun cleanup() {
        tempFiles.forEach { file ->
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Deleted temp file: ${file.name}")
            }
        }
        tempFiles.clear()
    }

    fun cleanupOldTempFiles(maxAgeMs: Long = 3600_000) {
        val currentTime = System.currentTimeMillis()

        context.cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < currentTime - maxAgeMs) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old temp file: ${file.name}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "TempFileManager"
    }
}
```

#### アクセス権限の設定

```xml
<!-- AndroidManifest.xml -->

<!-- カメラ -->
<uses-permission android:name="android.permission.CAMERA" />

<!-- マイク -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- ストレージ（Android 9 以下） -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- メディアアクセス（Android 13+） -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
```

---

### 3.2 ストレージ容量管理

#### 空き容量の確認方法

```kotlin
class StorageManager(private val context: Context) {

    data class StorageInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long
    ) {
        val totalMB: Long get() = totalBytes / 1024 / 1024
        val availableMB: Long get() = availableBytes / 1024 / 1024
        val usedMB: Long get() = usedBytes / 1024 / 1024
        val usagePercent: Int get() = ((usedBytes.toFloat() / totalBytes) * 100).toInt()
    }

    fun getStorageInfo(): StorageInfo {
        val filesDir = context.filesDir
        val statFs = StatFs(filesDir.absolutePath)

        val totalBytes = statFs.totalBytes
        val availableBytes = statFs.availableBytes
        val usedBytes = totalBytes - availableBytes

        return StorageInfo(totalBytes, availableBytes, usedBytes)
    }

    fun getAppStorageUsage(): Long {
        var totalSize = 0L

        // filesDir のサイズ
        context.filesDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
            }
        }

        // cacheDir のサイズ
        context.cacheDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
            }
        }

        return totalSize
    }

    fun getProjectStorageUsage(project: Project): Long {
        var totalSize = 0L

        project.segments.forEach { segment ->
            val file = File(context.filesDir, segment.uri)
            if (file.exists()) {
                totalSize += file.length()
            }
        }

        return totalSize
    }
}
```

#### 大量セグメント保存時の注意

```kotlin
@Composable
fun StorageWarningView(
    storageManager: StorageManager,
    segmentCount: Int
) {
    val storageInfo = remember { storageManager.getStorageInfo() }

    // 1セグメント ≈ 1-2MB と仮定
    val estimatedUsageMB = segmentCount * 2

    Column {
        // 現在の使用状況
        Text("ストレージ使用状況")
        LinearProgressIndicator(
            progress = storageInfo.usagePercent / 100f,
            modifier = Modifier.fillMaxWidth()
        )
        Text("${storageInfo.usedMB}MB / ${storageInfo.totalMB}MB (${storageInfo.usagePercent}%)")

        Spacer(modifier = Modifier.height(8.dp))

        // 空き容量チェック
        when {
            storageInfo.availableMB < 50 -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Red.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        "⚠️ ストレージ容量が非常に少なくなっています。" +
                        "録画を続けるとデータが失われる可能性があります。",
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            storageInfo.availableMB < 200 -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Yellow.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        "⚠️ ストレージ容量が少なくなっています。" +
                        "残り約${storageInfo.availableMB / 2}セグメント録画可能です。",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // プロジェクトの推定サイズ
        Text(
            "このプロジェクト: 約${estimatedUsageMB}MB (${segmentCount}セグメント)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
```

#### ストレージ不足時の警告

```kotlin
class StorageGuard(private val storageManager: StorageManager) {

    fun canRecord(): RecordPermission {
        val info = storageManager.getStorageInfo()

        return when {
            info.availableMB < 10 -> {
                RecordPermission.Denied("ストレージ容量が不足しています。録画できません。")
            }
            info.availableMB < 50 -> {
                RecordPermission.Warning("ストレージ容量が少なくなっています。録画後にエクスポートすることをお勧めします。")
            }
            else -> {
                RecordPermission.Allowed
            }
        }
    }

    fun canExport(estimatedSizeMB: Long): ExportPermission {
        val info = storageManager.getStorageInfo()

        // エクスポートには一時ファイル用に2倍の容量が必要
        val requiredMB = estimatedSizeMB * 2

        return when {
            info.availableMB < requiredMB -> {
                ExportPermission.Denied("エクスポートに必要な容量が不足しています。" +
                    "必要: ${requiredMB}MB、利用可能: ${info.availableMB}MB")
            }
            info.availableMB < requiredMB * 1.5 -> {
                ExportPermission.Warning("ストレージ容量が少なくなっています。" +
                    "エクスポート後に一時ファイルを削除してください。")
            }
            else -> {
                ExportPermission.Allowed
            }
        }
    }
}

sealed class RecordPermission {
    object Allowed : RecordPermission()
    data class Warning(val message: String) : RecordPermission()
    data class Denied(val message: String) : RecordPermission()
}

sealed class ExportPermission {
    object Allowed : ExportPermission()
    data class Warning(val message: String) : ExportPermission()
    data class Denied(val message: String) : ExportPermission()
}
```

---

## 4. エラーハンドリング

### 4.1 よくあるエラーと対策

#### ファイル保存失敗

```kotlin
fun saveSegmentWithRetry(
    segment: VideoSegment,
    maxRetries: Int = 3
): SaveResult {
    var lastError: Exception? = null

    repeat(maxRetries) { attempt ->
        try {
            saveSegment(segment)
            return SaveResult.Success
        } catch (e: IOException) {
            lastError = e
            Log.w(TAG, "Save attempt ${attempt + 1} failed", e)

            // 短い待機
            Thread.sleep(100)
        }
    }

    return SaveResult.Error(lastError?.message ?: "Unknown error")
}

sealed class SaveResult {
    object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}
```

#### ファイル削除失敗

```kotlin
fun deleteSegmentWithRetry(
    segment: VideoSegment,
    maxRetries: Int = 3
): Boolean {
    val file = File(context.filesDir, segment.uri)

    if (!file.exists()) {
        return true  // 既に存在しない
    }

    repeat(maxRetries) { attempt ->
        try {
            if (file.delete()) {
                Log.d(TAG, "File deleted on attempt ${attempt + 1}")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delete attempt ${attempt + 1} failed", e)
        }

        // GC を促進してファイルハンドルを解放
        System.gc()
        Thread.sleep(100)
    }

    Log.e(TAG, "Failed to delete file after $maxRetries attempts")
    return false
}
```

#### 権限不足

```kotlin
@Composable
fun PermissionHandler(
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    val context = LocalContext.current

    // 必要な権限のリスト
    val requiredPermissions = remember {
        mutableListOf<String>().apply {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            onPermissionGranted()
        } else {
            onPermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        val notGranted = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            onPermissionGranted()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
```

#### ストレージ不足

```kotlin
fun handleStorageInsufficient(
    context: Context,
    requiredMB: Long,
    availableMB: Long
) {
    val shortfallMB = requiredMB - availableMB

    // 削除可能なファイルを提案
    val cacheSize = context.cacheDir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() } / 1024 / 1024

    val message = buildString {
        appendLine("ストレージ容量が不足しています。")
        appendLine("必要: ${requiredMB}MB")
        appendLine("利用可能: ${availableMB}MB")
        appendLine("不足: ${shortfallMB}MB")

        if (cacheSize > shortfallMB) {
            appendLine()
            appendLine("キャッシュを削除すると ${cacheSize}MB 解放できます。")
        }
    }

    Log.e(TAG, message)
}

fun clearCache(context: Context): Long {
    var freedBytes = 0L

    context.cacheDir.walkTopDown().forEach { file ->
        if (file.isFile) {
            val size = file.length()
            if (file.delete()) {
                freedBytes += size
            }
        }
    }

    val freedMB = freedBytes / 1024 / 1024
    Log.d(TAG, "Cleared cache: ${freedMB}MB")

    return freedBytes
}
```

#### Composition 作成失敗

```kotlin
fun handleCompositionError(error: Exception): String {
    return when {
        error is OutOfMemoryError -> {
            "メモリ不足です。セグメント数を減らすか、アプリを再起動してください。"
        }
        error.message?.contains("width") == true ||
        error.message?.contains("height") == true -> {
            "動画ファイルが破損している可能性があります。該当するセグメントを削除してください。"
        }
        error.message?.contains("codec") == true -> {
            "サポートされていない動画形式です。別のデバイスで録画してください。"
        }
        error is CancellationException -> {
            "処理がキャンセルされました。"
        }
        else -> {
            "Composition の作成に失敗しました: ${error.message}"
        }
    }
}
```

---

### 4.2 ユーザーへの通知

#### トースト表示

```kotlin
@Composable
fun ShowToast(message: String) {
    val context = LocalContext.current

    LaunchedEffect(message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

// 使用例
when (deleteState) {
    is DeleteState.Success -> {
        ShowToast("セグメントを削除しました")
    }
    is DeleteState.Error -> {
        ShowToast("削除に失敗しました: ${deleteState.message}")
    }
    else -> {}
}
```

#### ダイアログ表示

```kotlin
@Composable
fun ErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text("再試行")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            if (onRetry != null) {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        }
    )
}
```

#### エラーログ記録

```kotlin
object ErrorLogger {
    private const val TAG = "ClipFlowError"

    fun logError(
        context: String,
        error: Exception,
        additionalInfo: Map<String, Any> = emptyMap()
    ) {
        val errorInfo = buildString {
            appendLine("Context: $context")
            appendLine("Error: ${error::class.simpleName}")
            appendLine("Message: ${error.message}")

            if (additionalInfo.isNotEmpty()) {
                appendLine("Additional Info:")
                additionalInfo.forEach { (key, value) ->
                    appendLine("  $key: $value")
                }
            }

            appendLine("Stack Trace:")
            appendLine(error.stackTraceToString())
        }

        Log.e(TAG, errorInfo)

        // 必要に応じてクラッシュレポートサービスに送信
        // Crashlytics.logException(error)
    }

    fun logWarning(context: String, message: String) {
        Log.w(TAG, "[$context] $message")
    }

    fun logInfo(context: String, message: String) {
        Log.i(TAG, "[$context] $message")
    }
}

// 使用例
try {
    deleteSegment(segment)
} catch (e: Exception) {
    ErrorLogger.logError(
        context = "SegmentDeletion",
        error = e,
        additionalInfo = mapOf(
            "segmentId" to segment.id,
            "segmentUri" to segment.uri,
            "segmentOrder" to segment.order
        )
    )
}
```

---

## 5. iOS版との対応関係

### PlayerView でのエクスポート機能

**iOS版**:

```swift
// PlayerView.swift
Button(action: { showExportOptions = true }) {
    Text("エクスポート")
}

.sheet(isPresented: $showExportOptions) {
    ExportOptionsView(
        project: project,
        onExport: { quality in
            exportVideo(quality: quality)
        }
    )
}

func exportVideo(quality: ExportQuality) {
    // AVAssetExportSession を使用
    let exportSession = AVAssetExportSession(
        asset: composition,
        presetName: quality.presetName
    )

    exportSession?.outputURL = outputURL
    exportSession?.outputFileType = .mp4

    exportSession?.exportAsynchronously {
        switch exportSession?.status {
        case .completed:
            // PHPhotoLibrary に保存
            saveToPhotoLibrary(url: outputURL)
        case .failed:
            showError(exportSession?.error)
        default:
            break
        }
    }
}
```

**Android版**:

```kotlin
// ExportScreen.kt
@Composable
fun ExportScreen(viewModel: ExportViewModel) {
    var showQualitySelector by remember { mutableStateOf(false) }

    Button(onClick = { showQualitySelector = true }) {
        Text("エクスポート")
    }

    if (showQualitySelector) {
        ExportOptionsDialog(
            onExport = { quality ->
                viewModel.exportToGallery(compositionUri, quality)
            },
            onDismiss = { showQualitySelector = false }
        )
    }
}

// ExportViewModel
class ExportViewModel : ViewModel() {
    fun exportToGallery(uri: Uri, quality: ExportQuality) {
        viewModelScope.launch {
            // Transformer を使用
            val exportedFile = exporter.exportComposition(uri, quality)

            // MediaStore に保存
            mediaStoreExporter.saveToGallery(exportedFile)
        }
    }
}
```

### ファイルシステム管理の比較

| 機能 | iOS版 | Android版 |
|------|-------|----------|
| アプリ専用ディレクトリ | Documents/ | context.filesDir |
| キャッシュ | Caches/ | context.cacheDir |
| ファイル削除 | FileManager.removeItem() | File.delete() |
| 権限 | NSPhotoLibraryAddUsageDescription | WRITE_EXTERNAL_STORAGE (SDK < 29) |
| ギャラリー保存 | PHPhotoLibrary | MediaStore |
| 容量確認 | URL.resourceValues(forKeys:) | StatFs |

### 対応関係表

| 機能 | iOS版 | Android版 |
|------|-------|----------|
| 削除確認 | Alert | AlertDialog |
| スワイプ削除 | .onDelete | SwipeToDismiss |
| order 再計算 | enumerated().forEach | mapIndexed |
| エクスポート | AVAssetExportSession | Transformer |
| ギャラリー保存 | PHPhotoLibrary | MediaStore |
| 権限要求 | PHPhotoLibrary.requestAuthorization | ActivityResultContracts |
| トースト | なし（Alert使用） | Toast |
| ストレージ確認 | FileManager.attributesOfFileSystem | StatFs |

---

## まとめ

### 削除フロー

```
1. ユーザーがスワイプまたはボタンをタップ
   ↓
2. 削除確認ダイアログ表示
   ↓
3. ファイルシステムからファイル削除
   ↓
4. segments リストから削除
   ↓
5. order を再計算
   ↓
6. SharedPreferences に保存
   ↓
7. UI を更新
```

### エクスポートフロー

```
1. エクスポート品質を選択
   ↓
2. ストレージ容量を確認
   ↓
3. Composition を MP4 にエクスポート
   ↓
4. MediaStore（ギャラリー）に保存
   ↓
5. 一時ファイルを削除
   ↓
6. 完了通知
```

### 重要なチェックポイント

1. **削除時**: ファイルが確実に削除されたか？order が連続しているか？
2. **エクスポート時**: 十分な空き容量があるか？権限があるか？
3. **ストレージ管理**: 定期的に一時ファイルを削除しているか？
4. **エラー処理**: すべてのエラーケースを適切に処理しているか？

これらのフローを正しく実装することで、iOS版と同等の機能を Android で実現できます。

---

*以上がセクション5b「セグメント管理フロー（削除・エクスポート・ストレージ）」です。*
