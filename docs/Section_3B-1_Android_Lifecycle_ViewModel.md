# セクション3B-1: Android版ライフサイクル管理（ViewModel と StateFlow）

## 3.2 Android版のライフサイクル管理パターン

### 3.2.1 ViewModel のライフサイクル

#### iOS版との対応関係

| iOS版 | Android版 |
|-------|----------|
| @StateObject private var projectManager = ProjectManager() | val viewModel: ProjectViewModel = viewModel() |
| ObservableObject | ViewModel() |
| MainView が保持 | Activity/NavBackStackEntry が保持 |
| @Published | StateFlow / MutableStateFlow |

#### ProjectViewModel の基本実装

```kotlin
// iOS版 ProjectManager.swift に相当
class ProjectViewModel(
    private val repository: ProjectRepository = ProjectRepository()
) : ViewModel() {

    // iOS版: @Published var projects: [Project] = []
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        // iOS版: init() { loadProjects() }
        loadProjects()
    }

    // プロジェクト読み込み
    private fun loadProjects() {
        viewModelScope.launch {
            val loadedProjects = repository.loadProjects()
            _projects.value = loadedProjects
        }
    }

    // プロジェクト作成
    fun createNewProject() {
        viewModelScope.launch {
            val newProject = repository.createProject()
            _projects.value = _projects.value + newProject
        }
    }

    // プロジェクト更新
    fun updateProject(updatedProject: Project) {
        viewModelScope.launch {
            repository.updateProject(updatedProject)

            _projects.value = _projects.value.map { project ->
                if (project.id == updatedProject.id) updatedProject else project
            }
        }
    }

    // プロジェクト削除
    fun deleteProject(project: Project) {
        viewModelScope.launch {
            repository.deleteProject(project)
            _projects.value = _projects.value.filter { it.id != project.id }
        }
    }

    // ✅ ViewModel 破棄時のクリーンアップ
    override fun onCleared() {
        super.onCleared()
        // Repository のリソース解放（必要に応じて）
        Log.d("ProjectViewModel", "ViewModel cleared")
    }
}
```

**iOS版との対応**:

- `init()` → `init { loadProjects() }`
- `@Published var projects` → `MutableStateFlow<List<Project>>`
- `saveProjects()` → `repository.updateProject()` で自動保存
- なし → `onCleared()` で明示的なクリーンアップ

#### ViewModelScope での処理

```kotlin
// iOS版: func createNewProject() -> Project { ... }
fun createNewProject() {
    // ✅ viewModelScope: ViewModel のライフサイクルに連動
    viewModelScope.launch {
        try {
            val newProject = repository.createProject()

            // UI スレッド（Main）で StateFlow を更新
            _projects.value = _projects.value + newProject

            Log.d("ProjectViewModel", "Project created: ${newProject.name}")
        } catch (e: Exception) {
            Log.e("ProjectViewModel", "Failed to create project", e)
        }
    }
}
```

**ViewModelScope の特性**:

| 特性 | 説明 |
|------|------|
| 自動キャンセル | ViewModel が破棄されると、実行中のコルーチンが自動的にキャンセルされる |
| デフォルトディスパッチャー | Dispatchers.Main で実行（UI スレッド） |
| 例外処理 | CoroutineExceptionHandler でグローバルに処理可能 |
| ライフサイクル連動 | Activity/Fragment が破棄されると ViewModel も破棄される |

**iOS版との違い**:

- iOS: 同期的な処理（`projects.append(newProject)`）
- Android: 非同期処理（`viewModelScope.launch { ... }`）

#### ViewModel 破棄時のリソース解放

```kotlin
class ProjectViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // ✅ ViewModel 破棄時に自動的に呼ばれる
    override fun onCleared() {
        super.onCleared()

        Log.d("ProjectViewModel", "ViewModel is being cleared")

        // Repository のクリーンアップ（必要に応じて）
        // 例: データベース接続のクローズ、リスナーの解除など
        repository.cleanup()
    }
}
```

**破棄タイミング**:

| シナリオ | ViewModel の状態 |
|---------|----------------|
| 画面回転（Configuration Change） | 破棄されない（システムが保持） |
| Navigation で戻る（popBackStack） | 破棄される（onCleared() 呼ばれる） |
| Activity/Fragment 終了 | 破棄される |
| アプリ終了 | 破棄される |

**iOS版との対応**:

- iOS: `deinit` または明示的なクリーンアップなし（ARC で自動解放）
- Android: `onCleared()` で明示的にクリーンアップ

#### 親 Activity/Fragment から ViewModel へのアクセス方法

##### Jetpack Compose での使用（推奨）

```kotlin
@Composable
fun MainScreen() {
    // ✅ viewModel() で ViewModel を取得（初回のみ作成、以降は再利用）
    val projectViewModel: ProjectViewModel = viewModel()

    // StateFlow を Compose State に変換
    val projects by projectViewModel.projects.collectAsState()

    ProjectListScreen(
        projects = projects,
        onCreateProject = { projectViewModel.createNewProject() },
        onDeleteProject = { projectViewModel.deleteProject(it) }
    )
}
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:6)
@StateObject private var projectManager = ProjectManager()
↓

// Android版
val projectViewModel: ProjectViewModel = viewModel()
```

##### 複数画面での ViewModel 共有（Navigation）

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            // ✅ navController の backStackEntry から ViewModel を取得
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry("projects")
            }
            val projectViewModel: ProjectViewModel = viewModel(parentEntry)

            val projects by projectViewModel.projects.collectAsState()

            ProjectListScreen(
                projects = projects,
                onNavigateToCamera = { project ->
                    navController.navigate("camera/${project.id}")
                }
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            // ✅ 親の backStackEntry から同じ ViewModel を取得
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry("projects")
            }
            val projectViewModel: ProjectViewModel = viewModel(parentEntry)

            CameraScreen(
                projectId = backStackEntry.arguments?.getString("projectId"),
                viewModel = projectViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版: MainView が projectManager を保持
@StateObject private var projectManager = ProjectManager()

// CameraView に渡さず、MainView 内でクロージャー経由でアクセス
CameraView(
    currentProject: project,
    onRecordingComplete: { videoSegment in
        // MainView の projectManager を直接参照
        projectManager.updateProject(updatedProject)
    }
)
```

**Android版の利点**:

- Navigation 経由で同じ ViewModel インスタンスを共有可能
- 画面遷移してもデータが保持される
- 明示的な状態の受け渡しが不要

#### 画面遷移時の ViewModel の状態保持

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") {
            // ✅ ViewModel は NavBackStackEntry に紐づいて保持される
            val projectViewModel: ProjectViewModel = viewModel()

            val projects by projectViewModel.projects.collectAsState()

            ProjectListScreen(
                projects = projects,
                onNavigateToCamera = { project ->
                    // 画面遷移しても ViewModel は破棄されない
                    navController.navigate("camera/${project.id}")
                }
            )
        }

        composable("camera/{projectId}") {
            CameraScreen(
                onBack = {
                    // ✅ 戻る時も "projects" の ViewModel は保持されている
                    navController.popBackStack()
                }
            )
        }
    }
}
```

**状態保持の仕組み**:

| 操作 | ViewModel の状態 |
|------|----------------|
| navigate("camera") | "projects" の ViewModel は保持される |
| popBackStack() で戻る | "projects" の ViewModel はそのまま残っている |
| "camera" 画面破棄 | "camera" の ViewModel（あれば）は破棄される |

**iOS版との対応**:

```swift
// iOS版: MainView が @StateObject で保持
.fullScreenCover(isPresented: .constant(currentScreen == .camera)) {
    CameraView(...)
}

// CameraView が破棄されても MainView の projectManager は保持される
```

**共通点**: どちらも親（MainView / "projects" NavBackStackEntry）が状態を保持

#### アプリ終了時の処理

##### Android版の自動保存戦略

```kotlin
class ProjectViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun updateProject(updatedProject: Project) {
        viewModelScope.launch {
            // ✅ 即座に Repository へ保存（iOS版と同じ戦略）
            repository.updateProject(updatedProject)

            // UI 更新
            _projects.value = _projects.value.map { project ->
                if (project.id == updatedProject.id) updatedProject else project
            }

            Log.d("ProjectViewModel", "Project updated and saved: ${updatedProject.name}")
        }
    }

    // ✅ アプリ終了時の明示的な保存は不要
    override fun onCleared() {
        super.onCleared()
        // データはすでに保存済みなので、特別な処理は不要
        Log.d("ProjectViewModel", "ViewModel cleared - All data already saved")
    }
}

class ProjectRepository(
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) {
    suspend fun updateProject(project: Project) {
        withContext(Dispatchers.IO) {
            // ✅ 即座に SharedPreferences に保存
            val projects = loadProjects().toMutableList()
            val index = projects.indexOfFirst { it.id == project.id }

            if (index != -1) {
                projects[index] = project
                saveProjects(projects)
            }
        }
    }

    private fun saveProjects(projects: List<Project>) {
        val json = gson.toJson(projects)
        sharedPreferences.edit().putString("projects", json).apply()
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:33-39)
func updateProject(_ updatedProject: Project) {
    if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
        projects[index] = updatedProject
        saveProjects()  // ✅ 即座に UserDefaults に保存
    }
}
```

**共通戦略**: どちらも「更新時に即座に保存」するため、アプリ終了時の特別な処理は不要

---

### 3.2.2 StateFlow / MutableStateFlow での状態管理

#### iOS版との対応関係

| iOS版 | Android版 |
|-------|----------|
| @Published var projects: [Project] | MutableStateFlow<List<Project>> |
| SwiftUI の自動再描画 | collectAsState() で自動再描画 |
| .sink { } で監視 | .collect { } で監視 |

#### MutableStateFlow の初期化と監視

```kotlin
class ProjectViewModel : ViewModel() {

    // ✅ Private: 外部から直接変更できない
    private val _projects = MutableStateFlow<List<Project>>(emptyList())

    // ✅ Public: 読み取り専用として公開
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // ✅ Private: 外部から直接変更できない
    private val _isLoading = MutableStateFlow(false)

    // ✅ Public: 読み取り専用として公開
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadProjects()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                val loadedProjects = repository.loadProjects()
                _projects.value = loadedProjects  // ✅ StateFlow 更新
            } catch (e: Exception) {
                Log.e("ProjectViewModel", "Failed to load projects", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:9)
@Published var projects: [Project] = []
↓

// Android版
private val _projects = MutableStateFlow<List<Project>>(emptyList())
val projects: StateFlow<List<Project>> = _projects.asStateFlow()
```

**パターンの違い**:

- iOS: `@Published` は public で外部から変更可能
- Android: `MutableStateFlow` は private、`StateFlow` として公開（読み取り専用）

#### collectAsState() で Compose が自動再描画される仕組み

```kotlin
@Composable
fun ProjectListScreen(
    viewModel: ProjectViewModel = viewModel()
) {
    // ✅ StateFlow を Compose State に変換
    val projects by viewModel.projects.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // ✅ projects が変更されると、このComposable全体が再描画される
    Column {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(projects) { project ->
                    ProjectItem(
                        project = project,
                        onClick = { viewModel.selectProject(project) }
                    )
                }
            }
        }
    }
}
```

**再描画の仕組み**:

1. `viewModel.projects.collectAsState()` が StateFlow を監視
2. ViewModel で `_projects.value = newList` が実行される
3. StateFlow が変更を検知
4. `projects` State が更新される
5. Compose が再コンポジション（再描画）を実行

**iOS版との対応**:

```swift
// iOS版 (ProjectListView.swift:5)
let projects: [Project]  // ← MainView から渡される

// MainView.swift:41
ProjectListView(
    projects: projectManager.projects,  // ← @Published を監視
    ...
)

// projectManager.projects が変更されると、ProjectListView が自動的に再描画される
```

#### 親 Composable から子 Composable への状態伝達

##### パターン1: State Hoisting（状態の持ち上げ）

```kotlin
// ✅ 親 Composable: ViewModel を持つ
@Composable
fun MainScreen(
    viewModel: ProjectViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsState()
    val selectedProject by viewModel.selectedProject.collectAsState()

    // ✅ 子 Composable に State とコールバックを渡す
    ProjectListScreen(
        projects = projects,
        onCreateProject = { viewModel.createNewProject() },
        onSelectProject = { viewModel.selectProject(it) },
        onDeleteProject = { viewModel.deleteProject(it) }
    )
}

// ✅ 子 Composable: State を受け取る（ViewModel を持たない）
@Composable
fun ProjectListScreen(
    projects: List<Project>,
    onCreateProject: () -> Unit,
    onSelectProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit
) {
    Column {
        Button(onClick = onCreateProject) {
            Text("New Project")
        }

        LazyColumn {
            items(projects) { project ->
                ProjectItem(
                    project = project,
                    onClick = { onSelectProject(project) },
                    onDelete = { onDeleteProject(project) }
                )
            }
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:40-61)
ProjectListView(
    projects: projectManager.projects,  // ← State を渡す
    onCreateProject: {
        createNewProject()  // ← コールバック
    },
    onOpenProject: { project in
        openProject(project, screen: .camera)
    },
    onDeleteProject: { project in
        confirmDeleteProject(project)
    }
)

// ProjectListView.swift:4-11
struct ProjectListView: View {
    let projects: [Project]  // ← State を受け取る
    let onCreateProject: () -> Void
    let onOpenProject: (Project) -> Void
    let onDeleteProject: (Project) -> Void
}
```

**共通パターン**: どちらも「State Hoisting」パターンを採用

##### パターン2: ViewModel の共有

```kotlin
@Composable
fun MainScreen() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "projects") {
        composable("projects") { backStackEntry ->
            // ✅ 親の ViewModel を取得
            val viewModel: ProjectViewModel = viewModel(backStackEntry)

            ProjectListScreen(
                viewModel = viewModel,  // ← ViewModel を直接渡す
                onNavigateToCamera = { project ->
                    navController.navigate("camera/${project.id}")
                }
            )
        }

        composable("camera/{projectId}") { backStackEntry ->
            // ✅ 同じ ViewModel を取得
            val parentEntry = navController.getBackStackEntry("projects")
            val viewModel: ProjectViewModel = viewModel(parentEntry)

            CameraScreen(
                viewModel = viewModel,  // ← 同じインスタンスを渡す
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

**iOS版との対応**: iOS版では ViewModel を直接渡さず、クロージャー経由でアクセス

```swift
CameraView(
    currentProject: project,
    onRecordingComplete: { videoSegment in
        // MainView の projectManager に直接アクセス
        projectManager.updateProject(updatedProject)
    }
)
```

#### リアクティブな状態更新パターン

##### パターン1: 単純な値の更新

```kotlin
class ProjectViewModel : ViewModel() {
    private val _selectedProjectId = MutableStateFlow<Int?>(null)
    val selectedProjectId: StateFlow<Int?> = _selectedProjectId.asStateFlow()

    fun selectProject(project: Project) {
        // ✅ 値を直接代入（リアクティブに更新）
        _selectedProjectId.value = project.id
    }
}
```

**iOS版との対応**:

```swift
// iOS版
@Published var selectedProject: Project?

func selectProject(_ project: Project) {
    selectedProject = project  // ← リアクティブに更新
}
```

##### パターン2: リストの更新

```kotlin
class ProjectViewModel : ViewModel() {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // ✅ 追加
    fun addProject(project: Project) {
        _projects.value = _projects.value + project
    }

    // ✅ 更新
    fun updateProject(updatedProject: Project) {
        _projects.value = _projects.value.map { project ->
            if (project.id == updatedProject.id) updatedProject else project
        }
    }

    // ✅ 削除
    fun deleteProject(projectId: Int) {
        _projects.value = _projects.value.filter { it.id != projectId }
    }

    // ✅ セグメント追加（ネストした更新）
    fun addSegment(projectId: Int, segment: VideoSegment) {
        _projects.value = _projects.value.map { project ->
            if (project.id == projectId) {
                project.copy(segments = project.segments + segment)
            } else {
                project
            }
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:33-39)
func updateProject(_ updatedProject: Project) {
    if let index = projects.firstIndex(where: { $0.id == updatedProject.id }) {
        projects[index] = updatedProject  // ← リアクティブに更新
        saveProjects()
    }
}
```

**違い**:

- iOS: インデックスで直接書き換え
- Android: イミュータブルな操作（map, filter, +）で新しいリストを作成

##### パターン3: 複雑な状態の更新（update 関数）

```kotlin
class ProjectViewModel : ViewModel() {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // ✅ update 関数を使った安全な更新
    fun addSegment(projectId: Int, segment: VideoSegment) {
        _projects.update { currentProjects ->
            currentProjects.map { project ->
                if (project.id == projectId) {
                    project.copy(segments = project.segments + segment)
                } else {
                    project
                }
            }
        }
    }
}
```

**update 関数の利点**:

- 並行更新時の競合を自動的に解決
- ラムダ内で現在の値を安全に参照できる
- より関数型プログラミング的

#### データ保存・更新・削除時の StateFlow 更新フロー

##### 完全なフロー例: プロジェクト作成

```kotlin
class ProjectViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun createNewProject() {
        viewModelScope.launch {
            try {
                // 1. ローディング開始
                _isLoading.value = true

                // 2. Repository でデータ作成 + 保存
                val newProject = repository.createProject()

                // 3. StateFlow 更新（UI に反映）
                _projects.value = _projects.value + newProject

                Log.d("ProjectViewModel", "Project created: ${newProject.name}")

            } catch (e: Exception) {
                Log.e("ProjectViewModel", "Failed to create project", e)
                // エラーハンドリング（ここでは省略）
            } finally {
                // 4. ローディング終了
                _isLoading.value = false
            }
        }
    }
}

class ProjectRepository(
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) {
    suspend fun createProject(): Project {
        return withContext(Dispatchers.IO) {
            // 1. 新しいプロジェクト作成
            val projects = loadProjects().toMutableList()
            val newProject = Project(
                id = System.currentTimeMillis().toInt(),
                name = "Project ${projects.size + 1}",
                segments = emptyList(),
                createdAt = System.currentTimeMillis()
            )

            // 2. リストに追加
            projects.add(newProject)

            // 3. SharedPreferences に即座に保存
            saveProjects(projects)

            // 4. 新しいプロジェクトを返す
            newProject
        }
    }

    private fun saveProjects(projects: List<Project>) {
        val json = gson.toJson(projects)
        sharedPreferences.edit().putString("projects", json).apply()
    }
}
```

**フロー図**:

```
User Action (Button Click)
    ↓
ViewModel.createNewProject()
    ↓
1. _isLoading.value = true
    ↓ (UI が自動的にローディング表示)
    ↓
2. repository.createProject()
    ↓ (バックグラウンドスレッドで実行)
    ↓ - 新しいProject作成
    ↓ - SharedPreferencesに保存
    ↓ - 新しいProjectを返す
    ↓
3. _projects.value = _projects.value + newProject
    ↓ (UI が自動的に新しいプロジェクトを表示)
    ↓
4. _isLoading.value = false
    ↓ (UI が自動的にローディング非表示)
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:21-30)
func createNewProject() -> Project {
    let projectName = "Project \(projects.count + 1)"
    let newProject = Project(name: projectName)

    projects.append(newProject)  // ← @Published が自動的に通知
    saveProjects()               // ← UserDefaults に保存

    return newProject
}
```

**違い**:

- iOS: 同期的、メインスレッドで実行
- Android: 非同期的、コルーチンで実行（バックグラウンドスレッド）

##### 完全なフロー例: セグメント追加

```kotlin
class ProjectViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun addSegmentToProject(projectId: Int, segment: VideoSegment) {
        viewModelScope.launch {
            try {
                // 1. Repository でセグメント追加 + 保存
                repository.addSegment(projectId, segment)

                // 2. StateFlow 更新（ネストした更新）
                _projects.update { currentProjects ->
                    currentProjects.map { project ->
                        if (project.id == projectId) {
                            project.copy(
                                segments = project.segments + segment,
                                lastModified = System.currentTimeMillis()
                            )
                        } else {
                            project
                        }
                    }
                }

                Log.d("ProjectViewModel", "Segment added to project $projectId")

            } catch (e: Exception) {
                Log.e("ProjectViewModel", "Failed to add segment", e)
            }
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (MainView.swift:78-87)
CameraView(
    onRecordingComplete: { videoSegment in
        guard let currentProject = projectManager.projects.first(where: { $0.id == project.id }) else { return }

        var updatedProject = currentProject
        updatedProject.segments.append(videoSegment)  // ← セグメント追加
        projectManager.updateProject(updatedProject)   // ← @Published が通知 + 保存

        selectedProject = updatedProject
    }
)
```

##### 完全なフロー例: プロジェクト削除

```kotlin
class ProjectViewModel(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            try {
                // 1. Repository でプロジェクト削除（動画ファイルも削除）
                repository.deleteProject(project)

                // 2. StateFlow 更新（フィルタリング）
                _projects.value = _projects.value.filter { it.id != project.id }

                Log.d("ProjectViewModel", "Project deleted: ${project.name}")

            } catch (e: Exception) {
                Log.e("ProjectViewModel", "Failed to delete project", e)
            }
        }
    }
}

class ProjectRepository(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val gson: Gson
) {
    suspend fun deleteProject(project: Project) {
        withContext(Dispatchers.IO) {
            // 1. 動画ファイルを物理削除
            project.segments.forEach { segment ->
                val file = File(context.filesDir, segment.uri)
                if (file.exists()) {
                    file.delete()
                    Log.d("ProjectRepository", "Video file deleted: ${segment.uri}")
                }
            }

            // 2. SharedPreferences から削除
            val projects = loadProjects().filter { it.id != project.id }
            saveProjects(projects)
        }
    }
}
```

**iOS版との対応**:

```swift
// iOS版 (ProjectManager.swift:435-449)
func deleteProject(_ project: Project) {
    deleteVideoFiles(for: project)  // 1. 物理ファイルを削除
    projects.removeAll { $0.id == project.id }  // 2. @Published が通知
    saveProjects()  // 3. UserDefaults に保存
}
```

---

### 3.2.3 iOS版 vs Android版のライフサイクル管理比較表

| 側面 | iOS版 | Android版 |
|------|-------|----------|
| 状態管理クラス | ObservableObject | ViewModel |
| 状態保持 | @StateObject | viewModel() |
| リアクティブプロパティ | @Published | MutableStateFlow / StateFlow |
| UI自動更新 | SwiftUI の自動再描画 | collectAsState() で再コンポジション |
| 非同期処理 | Task { } | viewModelScope.launch { } |
| ライフサイクル連動 | View のライフサイクルに連動 | NavBackStackEntry に連動 |
| リソース解放 | deinit または onDisappear | onCleared() |
| 画面回転時 | State 保持（自動） | State 保持（自動） |
| 画面遷移時 | 親 View が State 保持 | 親 NavBackStackEntry が ViewModel 保持 |
| データ保存 | UserDefaults（同期） | SharedPreferences（非同期） |
| 自動保存タイミング | 更新時に即座に保存 | 更新時に即座に保存 |

---

*以上がセクション3B-1「Android版ライフサイクル管理（ViewModel と StateFlow）」です。*
