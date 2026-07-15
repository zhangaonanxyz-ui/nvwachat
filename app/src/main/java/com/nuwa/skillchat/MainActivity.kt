package com.nuwa.skillchat

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.nuwa.skillchat.db.*
import com.nuwa.skillchat.network.*
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════
//  MainActivity — System bars + Compose entry
// ═══════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val giteeService = GiteeService()
    private val openRouterClient = OpenRouterClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make status bar transparent for edge-to-edge Apple aesthetic
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "nuwa-chat-db"
        ).build()

        setContent {
            AppleDarkTheme {
                val chatViewModel: ChatViewModel = viewModel {
                    ChatViewModel(db, giteeService, openRouterClient)
                }
                LaunchedEffect(Unit) { chatViewModel.syncSkillsFromGitee() }
                ChatAppScreen(chatViewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ChatViewModel — All state + business logic
// ═══════════════════════════════════════════════════════════════════

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
                                name = skill.name.replace(".md", "").replace("-perspective", "")
                                    .replace("-", " ").capitalize(),
                                prompt = promptText,
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            finally { isSyncing.value = false }
        }
    }

    fun startNewSession(skill: SkillEntity) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val sessionId = db.chatDao().createSession(
                ChatSessionEntity(title = "\u65b0\u5bf9\u8bdd", skillId = skill.id)
            )
            currentSessionId.value = sessionId
        }
    }

    fun deleteSession(sessionId: Long) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().deleteMessagesForSession(sessionId)
            db.chatDao().deleteSession(sessionId)
            if (currentSessionId.value == sessionId) currentSessionId.value = null
        }
    }

    fun clearCurrentSessionMessages() {
        val sid = currentSessionId.value ?: return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().deleteMessagesForSession(sid)
        }
    }

    fun sendMessage(text: String, apiKey: String, model: String) {
        val sessionId = currentSessionId.value ?: return
        if (text.isBlank()) return

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            db.chatDao().insertMessage(
                ChatMessageEntity(sessionId = sessionId, role = "user", content = text)
            )

            val session = db.chatDao().getAllSessionsFlow().first()
                .firstOrNull { it.id == sessionId } ?: return@launch
            val skill = db.skillDao().getSkillById(session.skillId) ?: return@launch

            val dbMessages = db.chatDao().getMessagesForSession(sessionId).first()
            val apiMessages = mutableListOf<OpenRouterClient.Message>()
            apiMessages.add(OpenRouterClient.Message("system", skill.prompt))
            dbMessages.forEach { apiMessages.add(OpenRouterClient.Message(it.role, it.content)) }

            isAiResponding.value = true
            streamingMessage.value = ""
            var buffer = ""

            try {
                val webSearchTools = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", "web_search")
                            put("description", "搜索互联网获取最新信息。当用户询问时事新闻、天气、股票、体育比分或其他需要实时数据的问题时使用此工具。")
                            put("parameters", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "搜索查询关键词")
                                    })
                                })
                                put("required", JSONArray().apply { put("query") })
                            })
                        })
                    })
                }
                openRouterClient.sendChatRequestStream(apiMessages, apiKey, model, webSearchTools).collect { chunk ->
                    buffer += chunk
                    streamingMessage.value = buffer
                }

                if (buffer.isNotEmpty()) {
                    db.chatDao().insertMessage(
                        ChatMessageEntity(sessionId = sessionId, role = "assistant", content = buffer)
                    )
                }

                // Auto-summarize title after 3 full rounds (3 user + 3 AI = 6 non-system messages)
                val all = db.chatDao().getMessagesForSession(sessionId).first()
                val nonSystem = all.filter { it.role != "system" }
                if (nonSystem.size >= 6) {
                    try {
                        // Only send the first 6 messages (first 3 rounds) for summarization
                        val firstSix = nonSystem.take(6).map { OpenRouterClient.Message(it.role, it.content) }
                        var summary: String? = null
                        var retryPrompt: String? = null
                        var attempts = 0
                        while (attempts < 3) {
                            summary = openRouterClient.sendSummarizeRequest(firstSix, apiKey, retryPrompt)
                            if (summary.isNullOrBlank()) break
                            if (summary.length <= 10) break
                            // Too long: retry with stricter prompt including the previous result
                            retryPrompt = "上一次你生成的标题是「$summary」，字数超过10字。请严格控制在10个中文字以内，只输出标题文字，不要任何解释、标点或引号。"
                            attempts++
                        }
                        if (!summary.isNullOrBlank() && summary.length <= 10) {
                            db.chatDao().updateSessionTitle(sessionId, summary)
                        } else if (!summary.isNullOrBlank()) {
                            // Fallback: truncate to 10 chars if still too long after retries
                            db.chatDao().updateSessionTitle(sessionId, summary.take(10))
                        }
                    } catch (_: Exception) { /* silent fallback */ }
                }
            } catch (e: Exception) {
                db.chatDao().insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId, role = "assistant",
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

// ═══════════════════════════════════════════════════════════════════
//  Apple Dark Theme — Pure OLED black, SF Pro typography, restrained blue
// ═══════════════════════════════════════════════════════════════════

private val AppleBlue      = Color(0xFF0A84FF)
private val AppleGreen     = Color(0xFF30D158)
private val AppleRed       = Color(0xFFFF453A)
private val AppleSurface   = Color(0xFF1C1C1E)
private val AppleCard      = Color(0xFF2C2C2E)
private val AppleBg        = Color(0xFF000000)
private val AppleText1     = Color(0xFFFFFFFF)
private val AppleText2     = Color(0xFFEBEBF5)
private val AppleText3     = Color(0xFF98989F)
private val AppleDivider   = Color(0xFF38383A)

@Composable
fun AppleDarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppleBlue,
            onPrimary = Color.White,
            primaryContainer = AppleBlue,
            onPrimaryContainer = Color.White,
            secondary = AppleCard,
            onSecondary = AppleText2,
            secondaryContainer = AppleCard,
            onSecondaryContainer = AppleText1,
            background = AppleBg,
            onBackground = AppleText1,
            surface = AppleSurface,
            onSurface = AppleText1,
            surfaceVariant = AppleCard,
            onSurfaceVariant = AppleText2,
            outline = AppleDivider,
            error = AppleRed
        ),
        typography = Typography(
            displayLarge  = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleLarge    = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            titleMedium   = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            bodyLarge     = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, lineHeight = 22.sp),
            bodyMedium    = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, lineHeight = 21.sp),
            labelLarge    = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            labelSmall    = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,  letterSpacing = 0.sp)
        ),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Main Screen — Drawer + Scaffold + TopBar + Input
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAppScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nuwa_chat_prefs", Context.MODE_PRIVATE) }

    var apiKey  by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }
    var model   by remember { mutableStateOf(prefs.getString("openrouter_model", "deepseek/deepseek-v4-pro") ?: "") }
    var showSettings by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val skillsState    = viewModel.skills.collectAsState(initial = emptyList())
    val sessionsState  = viewModel.sessions.collectAsState(initial = emptyList())
    val messagesState  = viewModel.currentMessages.collectAsState(initial = emptyList())
    var textInput      by remember { mutableStateOf("") }

    // Model dropdown
    val modelOptions = remember { mutableListOf("deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    // ─── Settings Dialog (Apple sheet style) ───────────────────────
    if (showSettings) {
        var tmpKey   by remember { mutableStateOf(apiKey) }
        var tmpModel by remember { mutableStateOf(model) }
        var ddExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = AppleCard,
            titleContentColor = AppleText1,
            textContentColor = AppleText3,
            title = { Text("\u8bbe\u7f6e", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = tmpKey, onValueChange = { tmpKey = it },
                        label = { Text("OpenRouter API Key", color = AppleText3) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppleText1, unfocusedTextColor = AppleText2,
                            focusedBorderColor = AppleBlue, unfocusedBorderColor = AppleDivider,
                            cursorColor = AppleBlue
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    // Model dropdown selector
                    ExposedDropdownMenuBox(
                        expanded = ddExpanded,
                        onExpandedChange = { ddExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = when (tmpModel) {
                                "deepseek/deepseek-v4-pro" -> "DeepSeek V4 Pro"
                                "deepseek/deepseek-v4-flash" -> "DeepSeek V4 Flash"
                                else -> tmpModel
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("\u6a21\u578b", color = AppleText3) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ddExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AppleText1, unfocusedTextColor = AppleText2,
                                focusedBorderColor = AppleBlue, unfocusedBorderColor = AppleDivider,
                                focusedContainerColor = AppleSurface, unfocusedContainerColor = AppleSurface
                            ),
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = ddExpanded,
                            onDismissRequest = { ddExpanded = false }
                        ) {
                            modelOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (opt) {
                                                "deepseek/deepseek-v4-pro" -> "DeepSeek V4 Pro"
                                                "deepseek/deepseek-v4-flash" -> "DeepSeek V4 Flash"
                                                else -> opt
                                            },
                                            color = if (opt == tmpModel) AppleBlue else AppleText1
                                        )
                                    },
                                    onClick = {
                                        tmpModel = opt
                                        ddExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        apiKey = tmpKey; model = tmpModel
                        prefs.edit().putString("openrouter_api_key", tmpKey)
                            .putString("openrouter_model", tmpModel).apply()
                        showSettings = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    enabled = tmpKey.isNotBlank() && tmpModel.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("\u4fdd\u5b58") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("\u53d6\u6d88", color = AppleBlue) }
            }
        )
    }

    // ─── Navigation Drawer ──────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AppleSurface,
                drawerContentColor = AppleText1
            ) {
                // Header
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("\u5973\u5a32", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AppleBlue)
                    Spacer(Modifier.height(2.dp))
                    Text("Skill \u667a\u80fd\u5bf9\u8bdd", fontSize = 13.sp, color = AppleText3)
                }
                Divider(color = AppleDivider)

                // Sessions
                Text("\u5386\u53f2\u5bf9\u8bdd", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = AppleText3, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sessionsState.value) { session ->
                        val selected = viewModel.currentSessionId.value == session.id
                        NavigationDrawerItem(
                            label = { Text(session.title, fontSize = 15.sp, maxLines = 1, color = if (selected) AppleBlue else AppleText1) },
                            selected = selected,
                            onClick = { viewModel.currentSessionId.value = session.id; scope.launch { drawerState.close() } },
                            badge = {
                                IconButton(onClick = { viewModel.deleteSession(session.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = AppleText3, modifier = Modifier.size(16.dp))
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = AppleCard,
                                unselectedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }

                Divider(color = AppleDivider)

                // Skills
                Text("\u9009\u62e9 Skill", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = AppleText3, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(skillsState.value) { skill ->
                        NavigationDrawerItem(
                            label = { Text(skill.name, fontSize = 15.sp, color = AppleText1) },
                            selected = false,
                            onClick = { viewModel.startNewSession(skill); scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Default.Add, contentDescription = null, tint = AppleBlue, modifier = Modifier.size(18.dp)) },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            containerColor = AppleBg,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        val current = sessionsState.value.find { it.id == viewModel.currentSessionId.value }
                        Text(
                            current?.title ?: "\u5973\u5a32",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppleText1
                        )
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { scope.launch { drawerState.open() } },
                                        onLongPress = { showSettings = true }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = AppleBlue)
                        }
                    },
                    actions = {
                        if (viewModel.currentSessionId.value != null) {
                            IconButton(onClick = { viewModel.clearCurrentSessionMessages() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = AppleText3)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = AppleText1
                    )
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).background(AppleBg)) {

                // ── Empty states ──
                if (apiKey.isBlank()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\u5973\u5a32", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = AppleBlue)
                            Spacer(Modifier.height(8.dp))
                            Text("\u8bf7\u5148\u914d\u7f6e API Key", color = AppleText3)
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = { showSettings = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                shape = RoundedCornerShape(20.dp)) { Text("\u5f00\u59cb\u914d\u7f6e") }
                        }
                    }
                } else if (viewModel.currentSessionId.value == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\u5973\u5a32", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = AppleBlue)
                            Spacer(Modifier.height(12.dp))
                            Text("\u6253\u5f00\u83dc\u5355\uff0c\u9009\u62e9\u4e00\u4e2a Skill \u5f00\u59cb", color = AppleText3, fontSize = 15.sp)
                        }
                    }
                } else {
                    // ── Chat messages ──
                    val listState = rememberLazyListState()
                    val msgs = messagesState.value.filter { it.role != "system" }
                    val streaming = viewModel.isAiResponding.value && viewModel.streamingMessage.value.isNotEmpty()
                    val total = msgs.size + (if (streaming) 1 else 0)

                    LaunchedEffect(msgs.size, viewModel.streamingMessage.value) {
                        if (total > 0) listState.animateScrollToItem(total - 1)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        reverseLayout = false
                    ) {
                        items(msgs) { message -> ChatBubble(message) }
                        if (streaming) {
                            item {
                                ChatBubble(ChatMessageEntity(
                                    sessionId = viewModel.currentSessionId.value ?: 0,
                                    role = "assistant",
                                    content = viewModel.streamingMessage.value
                                ))
                            }
                        }
                    }
                }

                // ── Input bar ──
                if (viewModel.currentSessionId.value != null && apiKey.isNotBlank()) {
                    Surface(
                        color = AppleSurface,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("\u6d88\u606f", color = AppleText3) },
                                colors = TextFieldDefaults.colors(
                                    focusedTextColor = AppleText1,
                                    unfocusedTextColor = AppleText2,
                                    focusedContainerColor = AppleCard,
                                    unfocusedContainerColor = AppleCard,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = AppleBlue
                                ),
                                shape = RoundedCornerShape(20.dp),
                                maxLines = 4
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.sendMessage(textInput, apiKey, model)
                                    textInput = ""
                                },
                                enabled = textInput.isNotBlank(),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppleBlue,
                                    disabledContainerColor = AppleCard
                                ),
                                modifier = Modifier.size(40.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send",
                                    tint = if (textInput.isBlank()) AppleText3 else Color.White,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Chat Bubble — iMessage-style, gradient, selectable
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // User bubble — blue gradient (iMessage right)
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 280.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF0A84FF), Color(0xFF0066CC))
                            ),
                            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                setTextIsSelectable(true)
                                setTextColor(Color.White.toArgb())
                                textSize = 15f
                            }
                        },
                        update = { tv -> tv.text = message.content; tv.setTextColor(Color.White.toArgb()) }
                    )
                }
            }
        } else {
            // AI bubble — dark card (iMessage left)
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = AppleCard,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                MarkdownContent(
                    content = message.content,
                    textColor = AppleText2,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Markdown Renderer — Dark-theme aware, dark code blocks
// ═══════════════════════════════════════════════════════════════════

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
                setLinkTextColor(AppleBlue.toArgb())
                highlightColor = AppleBlue.copy(alpha = 0.3f).toArgb()
            }
        },
        update = { textView ->
            textView.setTextColor(textColor.toArgb())
            try {
                val markwon = Markwon.builder(context)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .build()
                markwon.setMarkdown(textView, content)
            } catch (e: Exception) {
                // Fallback: if Markwon fails, show plain text
                textView.text = content
            }
        }
    )
}
