package com.focusguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.detection.AppCategory
import com.focusguard.app.detection.AppClassifier

@Composable
fun AppControlScreen(onOpenUsageLimits: () -> Unit = {}) {
    var selectedTab by remember { mutableStateOf(0) }
    var filterText by remember { mutableStateOf("") }

    val defaultApps = remember {
        listOf(
            "com.tencent.tmgp.sgame" to ("王者荣耀" to AppCategory.GAME),
            "com.miHoYo.Yuanshen" to ("原神" to AppCategory.GAME),
            "com.tencent.tmgp.pubgmhd" to ("和平精英" to AppCategory.GAME),
            "cn.xuexi.android" to ("学习强国" to AppCategory.STUDY),
            "com.dedao.npp" to ("得到" to AppCategory.STUDY),
            "com.youdao.dict" to ("有道词典" to AppCategory.STUDY),
            "tv.danmaku.bili" to ("哔哩哔哩" to AppCategory.VIDEO),
            "com.tencent.qqlive" to ("腾讯视频" to AppCategory.VIDEO),
            "com.ss.android.ugc.aweme" to ("抖音" to AppCategory.SHORT_VIDEO),
            "com.smile.gifmaker" to ("快手" to AppCategory.SHORT_VIDEO),
            "com.sina.weibo" to ("新浪微博" to AppCategory.SOCIAL),
            "com.tencent.mm" to ("微信" to AppCategory.SOCIAL),
            "com.xingin.xhs" to ("小红书" to AppCategory.SOCIAL),
            "com.zhihu.android" to ("知乎" to AppCategory.SOCIAL)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "应用管控",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Text(
            text = "设置各应用的检测策略，纯游戏自动拦截，视频/社交由 AI 动态识别",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f)
        )

        // 使用时长入口
        Card(
            modifier = Modifier
                .fillMaxWidth(),
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
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f)
                )
            }
        }

        // Search Bar
        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = { Text("搜索应用...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        // Filter Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF263238),
            contentColor = Color.White
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("全部 (${defaultApps.size})", modifier = Modifier.padding(12.dp))
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

        val filteredApps = remember(selectedTab, filterText) {
            defaultApps.filter { (pkg, pair) ->
                val (name, cat) = pair
                val matchesSearch = name.contains(filterText, ignoreCase = true) || pkg.contains(filterText, ignoreCase = true)
                val matchesTab = when (selectedTab) {
                    1 -> cat == AppCategory.GAME || cat == AppCategory.SHORT_VIDEO
                    2 -> cat == AppCategory.STUDY
                    3 -> AppClassifier.needsAiDetection(cat)
                    else -> true
                }
                matchesSearch && matchesTab
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredApps) { (pkg, pair) ->
                val (name, cat) = pair
                AppControlItem(
                    packageName = pkg,
                    appName = name,
                    category = cat
                )
            }
        }
    }
}

@Composable
fun AppControlItem(
    packageName: String,
    appName: String,
    category: AppCategory
) {
    val (statusLabel, statusColor, strategyText) = when (category) {
        AppCategory.GAME -> Triple("禁止", Color(0xFFF44336), "前台自动触发锁机/退出")
        AppCategory.STUDY -> Triple("允许", Color(0xFF4CAF50), "豁免检测，放心使用")
        AppCategory.SYSTEM -> Triple("系统", Color(0xFF9E9E9E), "系统应用")
        AppCategory.VIDEO -> Triple("AI 分析", Color(0xFFFF9800), "结合文字/视觉智能识别")
        AppCategory.SHORT_VIDEO -> Triple("AI 分析", Color(0xFFFF9800), "结合文字/视觉智能识别")
        AppCategory.SOCIAL -> Triple("AI 分析", Color(0xFFFF9800), "结合文字/视觉智能识别")
        AppCategory.UNKNOWN -> Triple("AI 分析", Color(0xFFFF9800), "未知应用动态检测")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF263238)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = packageName,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strategyText,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
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
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
