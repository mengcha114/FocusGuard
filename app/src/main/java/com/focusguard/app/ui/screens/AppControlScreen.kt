package com.focusguard.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppCategoryStore
import com.focusguard.app.detection.AppClassifier
import com.focusguard.app.detection.AppInventory
import com.focusguard.app.detection.InstalledApp
import kotlinx.coroutines.launch

/**
 * 应用管控页。
 *
 * 列出设备上实际安装的可启动应用（带图标与名称），
 * 支持搜索、分类筛选，点击应用可手动指定其分类（覆盖自动识别）。
 */
@Composable
fun AppControlScreen(
    onOpenUsageLimits: () -> Unit = {},
    onPickAppForLimit: (InstalledApp) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var filterText by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editingApp by remember { mutableStateOf<InstalledApp?>(null) }

    // 读取已安装应用（含自动识别出的分类）
    LaunchedEffect(Unit) {
        loading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = AppCategoryStore(context)
            apps = AppInventory.listLaunchableApps(context, store)
        }
        loading = false
    }

    val filteredApps = remember(apps, selectedTab, filterText) {
        apps.filter { app ->
            val matchesSearch = filterText.isBlank() ||
                app.label.contains(filterText, ignoreCase = true) ||
                app.packageName.contains(filterText, ignoreCase = true)
            val matchesTab = when (selectedTab) {
                1 -> app.category == AppCategory.GAME || app.category == AppCategory.SHORT_VIDEO
                2 -> app.category == AppCategory.STUDY
                3 -> AppClassifier.needsAiDetection(app.category)
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("应用管控", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "选择要管控的应用，纯游戏自动拦截，视频/社交由 AI 动态识别",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f)
        )

        // ── 使用时长入口 ──────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F23)),
            onClick = onOpenUsageLimits
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "应用时长管理",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            "设置触发检测或封锁的使用时长阈值",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
            }
        }

        // ── 搜索 ──────────────────────────────────────
        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text("搜索应用...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        // ── 分类 Tab ──────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF263238),
            contentColor = Color.White
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("全部 (${apps.size})", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("游戏/娱乐", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("学习/办公", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                Text("需 AI 识别", modifier = Modifier.padding(12.dp))
            }
        }

        // ── 应用列表 ──────────────────────────────────
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFD0BCFF))
                    Spacer(Modifier.height(10.dp))
                    Text("正在读取应用列表…", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppControlRow(
                        app = app,
                        onClick = { editingApp = app }
                    )
                }
            }
        }
    }

    // ── 分类设置弹窗 ─────────────────────────────────
    val scope = rememberCoroutineScope()
    editingApp?.let { app ->
        CategoryEditSheet(
            app = app,
            onDismiss = { editingApp = null },
            onCategoryChanged = { category ->
                val store = AppCategoryStore(context)
                if (category == null) {
                    store.clearUserOverride(app.packageName)
                } else {
                    store.setUserOverride(app.packageName, category)
                }
                AppInventory.invalidate()
                editingApp = null
                // 重新加载以刷新分类显示
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val store2 = AppCategoryStore(context)
                        apps = AppInventory.listLaunchableApps(context, store2, forceRefresh = true)
                    }
                }
            }
        )
    }
}

/** 应用行：图标 + 名称 + 分类标签。 */
@Composable
private fun AppControlRow(app: InstalledApp, onClick: () -> Unit) {
    val (statusLabel, statusColor) = when (app.category) {
        AppCategory.GAME -> "游戏" to Color(0xFFF44336)
        AppCategory.STUDY -> "学习" to Color(0xFF4CAF50)
        AppCategory.SYSTEM -> "系统" to Color(0xFF9E9E9E)
        AppCategory.VIDEO -> "视频" to Color(0xFFFF9800)
        AppCategory.SHORT_VIDEO -> "短视频" to Color(0xFFFF9800)
        AppCategory.SOCIAL -> "社交" to Color(0xFFFF9800)
        AppCategory.UNKNOWN -> "未知" to Color(0xFF90A4AE)
    }

    val iconBitmap: ImageBitmap? = remember(app.packageName) {
        runCatching { app.icon?.toBitmap(48, 48)?.asImageBitmap() }.getOrNull()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        app.label.take(1),
                        fontSize = 18.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }

            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** 手动指定应用分类的底部弹窗。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditSheet(
    app: InstalledApp,
    onDismiss: () -> Unit,
    onCategoryChanged: (AppCategory?) -> Unit
) {
    val context = LocalContext.current
    val categoryStore = remember { AppCategoryStore(context) }
    var selected by remember {
        mutableStateOf(
            categoryStore.getUserOverride(app.packageName) ?: app.category
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1B1F)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = app.label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = app.packageName,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(4.dp))
            Text("手动指定分类（将覆盖自动识别）", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))

            val options = listOf(
                AppCategory.GAME to "游戏",
                AppCategory.STUDY to "学习/办公",
                AppCategory.VIDEO to "视频",
                AppCategory.SHORT_VIDEO to "短视频",
                AppCategory.SOCIAL to "社交",
                AppCategory.SYSTEM to "系统"
            )
            options.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { (cat, label) ->
                        FilterChip(
                            selected = selected == cat,
                            onClick = { selected = cat },
                            label = { Text(label, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("取消") }
                Button(
                    onClick = {
                        onCategoryChanged(selected)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F378B))
                ) { Text("确认") }
            }
        }
    }
}
