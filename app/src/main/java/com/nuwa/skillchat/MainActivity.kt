package com.nuwa.skillchat

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.nuwa.skillchat.db.*
import com.nuwa.skillchat.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val giteeService = GiteeService()
    private val openRouterClient = OpenRouterClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "nuwa-chat-db"
        ).build()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val chatViewModel: ChatViewModel = viewModel {
                    ChatViewModel(db, giteeService, openRouterClient)
                }
                
                LaunchedEffect(Unit) {
                    chatViewModel.syncSkillsFromGitee()
                }

                ChatAppScreen(chatViewModel)
            }
        }
    }
}

class ChatViewModel(
    private val db: AppDatabase,
    private val giteeService: GiteeService,
    private val openRouterClient: OpenRouterClient
) : ViewModel() {

    val skills: Flow<List<SkillEntity>> = db.skillDao().getAllSkillsFlow()
    val sessions: Flow<List<ChatSessionEntity>> = db.chatDao().getAllSessionsFlow()
    
    val currentSessionId = mutableStateOf<Long?>(null)
    val isSyncing = mutableStateOf(false)
    val isAiResponding = mutableStateOf(false)

    val currentMessages: Flow<List<ChatMessageEntity>> = snapshotFlow { currentSessionId.value }
        .flatMapLatest { id ->
            if (id != null) db.chatDao().getMessagesForSession(id) else flowOf(emptyList())
        }

    fun syncSkillsFromGitee() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            isSyncing.value = true
            try {
                val onlineSkills = giteeService.fetchSkillsList()
                val activeIds = onlineSkills.map { it.name }
                
                db.skillDao().deleteOldSkills(activeIds)
                
                onlineSkills.forEach { skill ->
                    val promptText = giteeService.fetchSkillPrompt(skill.path)
                    if (promptText != null) {
                        db.skillDao().insertSkill(
                            SkillEntity(
                                id = skill.name,
                                name = skill.name.replace(".md", "").replace("-perspective", "").replace("-", " ").capitalize(),
                                prompt = promptText,
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSyncing.value = false
            }
        }
    }

    fun startNewSession(skill: SkillEntity) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val sessionId = db.chatDao().createSession(
                ChatSessionEntity(
                    title = "对话: ${skill.name}",
                    skillId = skill.id
                )
            )
            currentSessionId.value = sessionId
        }
    }

    fun deleteSession(sessionId: Long) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().deleteMessagesForSession(sessionId)
            db.chatDao().deleteSession(sessionId)
            if (currentSessionId.value == sessionId) {
                currentSessionId.value = null
            }
        }
    }

    fun sendMessage(text: String, apiKey: String, model: String) {
        val sessionId = currentSessionId.value ?: return
        if (text.isBlank() || isAiResponding.value) return

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().insertMessage(ChatMessageEntity(sessionId = sessionId, role = "user", content = text))
            
            val session = db.chatDao().getAllSessionsFlow().first().firstOrNull { it.id == sessionId } ?: return@launch
            val skill = db.skillDao().getSkillById(session.skillId) ?: return@launch
            
            val dbMessages = db.chatDao().getMessagesForSession(sessionId).first()
            val apiMessages = mutableListOf<OpenRouterClient.Message>()
            
            apiMessages.add(OpenRouterClient.Message("system", skill.prompt))
            dbMessages.forEach { msg ->
                apiMessages.add(OpenRouterClient.Message(msg.role, msg.content))
            }

            isAiResponding.value = true
            var aiResponseBuffer = ""
            
            val aiMessagePlaceholderId = db.chatDao().insertMessage(
                ChatMessageEntity(sessionId = sessionId, role = "assistant", content = "")
            )

            // Pass apiKey and model dynamically
            openRouterClient.sendChatRequestStream(apiMessages, apiKey, model).collect { chunk ->
                aiResponseBuffer += chunk
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        id = aiMessagePlaceholderId,
                        sessionId = sessionId,
                        role = "assistant",
                        content = aiResponseBuffer
                    )
                )
            }
            isAiResponding.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("nuwa_chat_prefs", Context.MODE_PRIVATE) }
    
    // API config states loaded from SharedPreferences
    var savedApiKey by remember { mutableStateOf(sharedPrefs.getString("openrouter_api_key", "") ?: "") }
    var savedModel by remember { mutableStateOf(sharedPrefs.getString("openrouter_model", "deepseek/deepseek-v4-pro") ?: "") }

    var showSettingsDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val skillsState = viewModel.skills.collectAsState(initial = emptyList())
    val sessionsState = viewModel.sessions.collectAsState(initial = emptyList())
    val messagesState = viewModel.currentMessages.collectAsState(initial = emptyList())
    
    var textInput by remember { mutableStateOf("") }

    // Settings Dialog UI
    if (showSettingsDialog) {
        var tempKey by remember { mutableStateOf(savedApiKey) }
        var tempModel by remember { mutableStateOf(savedModel) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("API 配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        label = { Text("OpenRouter API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        label = { Text("大模型名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    savedApiKey = tempKey
                    savedModel = tempModel
                    sharedPrefs.edit().apply {
                        putString("openrouter_api_key", tempKey)
                        putString("openrouter_model", tempModel)
                        apply()
                    }
                    showSettingsDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("会话管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Divider()
                
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sessionsState.value) { session ->
                        NavigationDrawerItem(
                            label = { Text(session.title) },
                            selected = viewModel.currentSessionId.value == session.id,
                            onClick = {
                                viewModel.currentSessionId.value = session.id
                                scope.launch { drawerState.close() }
                            },
                            badge = {
                                IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                }
                            },
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                
                Divider()
                Text("从 Gitee 选择 Skill", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(skillsState.value) { skill ->
                        NavigationDrawerItem(
                            label = { Text(skill.name) },
                            selected = false,
                            onClick = {
                                viewModel.startNewSession(skill)
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.Default.Add, contentDescription = "Add") },
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        val currentSession = sessionsState.value.find { it.id == viewModel.currentSessionId.value }
                        Text(currentSession?.title ?: "女娲 · Skill 智能对话") 
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        if (viewModel.isSyncing.value) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { viewModel.syncSkillsFromGitee() }) {
                                Text("同步 Gitee", color = Color.Cyan)
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (savedApiKey.isBlank()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("请先点击右上角设置图标配置你的 OpenRouter API Key", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showSettingsDialog = true }) {
                                Text("立即配置")
                            }
                        }
                    }
                } else if (viewModel.currentSessionId.value == null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("点击左上角菜单，拉取并选择一个 Skill 开始聊天", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        reverseLayout = false
                    ) {
                        items(messagesState.value.filter { it.role != "system" }) { message ->
                            ChatBubble(message)
                        }
                    }
                }

                if (viewModel.currentSessionId.value != null && savedApiKey.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("向 AI 提问...") },
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.sendMessage(textInput, savedApiKey, savedModel)
                                textInput = ""
                            },
                            enabled = !viewModel.isAiResponding.value
                        ) {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = color,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
