package com.nuwa.skillchat

import android.content.Context
import android.os.Bundle
import android.widget.TextView
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin

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
    val streamingMessage = mutableStateOf("")

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
                    title = "\u5bf9\u8bdd: ${skill.name}",
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

    fun clearCurrentSessionMessages() {
        val sessionId = currentSessionId.value ?: return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().deleteMessagesForSession(sessionId)
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
            streamingMessage.value = ""
            var aiResponseBuffer = ""
            
            try {
                openRouterClient.sendChatRequestStream(apiMessages, apiKey, model).collect { chunk ->
                    aiResponseBuffer += chunk
                    streamingMessage.value = aiResponseBuffer
                }
                
                // Stream finished -> Write to DB once
                if (aiResponseBuffer.isNotEmpty()) {
                    db.chatDao().insertMessage(
                        ChatMessageEntity(
                            sessionId = sessionId,
                            role = "assistant",
                            content = aiResponseBuffer
                        )
                    )
                }

                // Auto-summarize session title after first AI reply
                val allMessages = db.chatDao().getMessagesForSession(sessionId).first()
                val nonSystemMessages = allMessages.filter { it.role != "system" }
                if (nonSystemMessages.size <= 2) {
                    try {
                        val summaryMessages = nonSystemMessages.map {
                            OpenRouterClient.Message(it.role, it.content)
                        }
                        val summary = openRouterClient.sendSummarizeRequest(summaryMessages, apiKey)
                        if (!summary.isNullOrBlank()) {
                            db.chatDao().updateSessionTitle(sessionId, summary)
                        }
                    } catch (_: Exception) { /* Silent fallback */ }
                }
            } catch (e: Exception) {
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = "API\u8fde\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216\u8bbe\u7f6e: ${e.localizedMessage}"
                    )
                )
            } finally {
                isAiResponding.value = false
                streamingMessage.value = ""
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("nuwa_chat_prefs", Context.MODE_PRIVATE) }
    
    var savedApiKey by remember { mutableStateOf(sharedPrefs.getString("openrouter_api_key", "") ?: "") }
    var savedModel by remember { mutableStateOf(sharedPrefs.getString("openrouter_model", "deepseek/deepseek-v4-pro") ?: "") }

    var showSettingsDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    val skillsState = viewModel.skills.collectAsState(initial = emptyList())
    val sessionsState = viewModel.sessions.collectAsState(initial = emptyList())
    val messagesState = viewModel.currentMessages.collectAsState(initial = emptyList())
    
    var textInput by remember { mutableStateOf("") }

    // Settings Dialog
    if (showSettingsDialog) {
        var tempKey by remember { mutableStateOf(savedApiKey) }
        var tempModel by remember { mutableStateOf(savedModel) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("API \u914d\u7f6e") },
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
                        label = { Text("\u5927\u6a21\u578b\u540d\u79f0") },
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
                    Text("\u4fdd\u5b58")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("\u53d6\u6d88")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("\u4f1a\u8bdd\u7ba1\u7406", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
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
                Text("\u9009\u62e9 Skill \u5f00\u59cb\u5bf9\u8bdd", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                
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
                        Text(currentSession?.title ?: "\u5973\u5a32 \u00b7 Skill \u667a\u80fd\u5bf9\u8bdd") 
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (viewModel.currentSessionId.value != null) {
                            IconButton(onClick = { viewModel.clearCurrentSessionMessages() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear Chat", tint = Color.LightGray)
                            }
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                            Text("\u8bf7\u5148\u70b9\u51fb\u53f3\u4e0a\u89d2\u8bbe\u7f6e\u56fe\u6807\u914d\u7f6e\u4f60\u7684 OpenRouter API Key", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showSettingsDialog = true }) {
                                Text("\u7acb\u5373\u914d\u7f6e")
                            }
                        }
                    }
                } else if (viewModel.currentSessionId.value == null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("\u70b9\u51fb\u5de6\u4e0a\u89d2\u83dc\u5355\uff0c\u9009\u62e9\u4e00\u4e2a Skill \u5f00\u59cb\u804a\u5929", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    // Chat message list with auto-scroll
                    val listState = rememberLazyListState()
                    val messages = messagesState.value.filter { it.role != "system" }
                    val isStreaming = viewModel.isAiResponding.value && viewModel.streamingMessage.value.isNotEmpty()
                    val totalItems = messages.size + (if (isStreaming) 1 else 0)

                    // Auto-scroll to bottom on new messages or streaming updates
                    LaunchedEffect(messages.size, viewModel.streamingMessage.value) {
                        if (totalItems > 0) {
                            listState.animateScrollToItem(totalItems - 1)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        reverseLayout = false
                    ) {
                        items(messages) { message ->
                            ChatBubble(message)
                        }
                        // Streaming message bubble
                        if (isStreaming) {
                            item {
                                ChatBubble(
                                    ChatMessageEntity(
                                        sessionId = viewModel.currentSessionId.value ?: 0,
                                        role = "assistant",
                                        content = viewModel.streamingMessage.value
                                    )
                                )
                            }
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
                            placeholder = { Text("\u5411 AI \u63d0\u95ee...") },
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
                            Text("\u53d1\u9001")
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
    val bgColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (isUser) {
                // User messages: plain text, selectable
                AndroidView(
                    modifier = Modifier.padding(12.dp),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextIsSelectable(true)
                            setTextColor(textColor.toArgb())
                            textSize = 15f
                        }
                    },
                    update = { tv ->
                        tv.text = message.content
                        tv.setTextColor(textColor.toArgb())
                    }
                )
            } else {
                // AI messages: Markwon rendered Markdown + LaTeX
                MarkdownContent(
                    content = message.content,
                    textColor = textColor,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun MarkdownContent(content: String, textColor: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                textSize = 15f
                setTextColor(textColor.toArgb())
                setLinkTextColor(android.graphics.Color.parseColor("#64B5F6"))
            }
        },
        update = { textView ->
            textView.setTextColor(textColor.toArgb())
            try {
                val markwon = Markwon.builder(context)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .usePlugin(JLatexMathPlugin.create(textView.textSize) { builder ->
                        builder.inlinesEnabled(true)
                    })
                    .build()
                markwon.setMarkdown(textView, content)
            } catch (e: Exception) {
                // Fallback: if Markwon/LaTeX fails, show plain text
                textView.text = content
            }
        }
    )
}
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
import androidx.compose.foundation.text.selection.SelectionContainer

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
    val streamingMessage = mutableStateOf("")

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

    fun clearCurrentSessionMessages() {
        val sessionId = currentSessionId.value ?: return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().deleteMessagesForSession(sessionId)
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
            streamingMessage.value = "" // Reset streaming buffer
            var aiResponseBuffer = ""
            
            try {
                // Pass apiKey and model dynamically - Only update memory state for 60fps rendering
                openRouterClient.sendChatRequestStream(apiMessages, apiKey, model).collect { chunk ->
                    aiResponseBuffer += chunk
                    streamingMessage.value = aiResponseBuffer
                }
                
                // Stream finished successfully -> Write to Room DB exactly ONCE
                if (aiResponseBuffer.isNotEmpty()) {
                    db.chatDao().insertMessage(
                        ChatMessageEntity(
                            sessionId = sessionId,
                            role = "assistant",
                            content = aiResponseBuffer
                        )
                    )
                }
            } catch (e: Exception) {
                // Save error message to DB exactly ONCE
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        content = "API连接失败，请检查网络或设置: ${e.localizedMessage}"
                    )
                )
            } finally {
                isAiResponding.value = false
                streamingMessage.value = "" // Reset buffer after saving
            }
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
                        if (viewModel.currentSessionId.value != null) {
                            IconButton(onClick = { viewModel.clearCurrentSessionMessages() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear Chat", tint = Color.LightGray)
                            }
                        }
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
                        // Render temporary live streaming content in UI from memory without DB writes
                        if (viewModel.isAiResponding.value && viewModel.streamingMessage.value.isNotEmpty()) {
                            item {
                                ChatBubble(
                                    ChatMessageEntity(
                                        sessionId = viewModel.currentSessionId.value ?: 0,
                                        role = "assistant",
                                        content = viewModel.streamingMessage.value
                                    )
                                )
                            }
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
            SelectionContainer {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
