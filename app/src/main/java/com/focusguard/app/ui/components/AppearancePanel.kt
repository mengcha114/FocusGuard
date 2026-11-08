package com.focusguard.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.Appearance
import com.focusguard.app.ui.theme.AppearanceState
import com.focusguard.app.ui.theme.ImageBackground
import com.focusguard.app.ui.theme.inkCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个性化外观面板：背景、配色、光晕、卡片、圆角、字号、锁机页元素、动画。
 * 所有修改立即写入 [AppearanceState]，全应用（含锁机页）即时生效。
 *
 * 顶部是实时预览：改配色/卡片/圆角/字号能当场看到效果，不用退出再进设置。
 */
@Composable
fun AppearancePanel() {
    val context = LocalContext.current
    val a = AppearanceState.get(context)
    fun set(transform: (Appearance) -> Appearance) = AppearanceState.update(context, transform)

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 选图结果处理：系统相册选择器与文档选择器共用同一段逻辑
    fun handlePicked(uri: android.net.Uri?) {
        com.focusguard.app.ui.theme.AppearanceState.clearPickerOpened(context)
        if (uri == null) return
        importing = true
        importError = false
        // 关键：解码 + 缩放 + 压缩整张照片必须离开主线程。
        // 原来在主线程同步 importImage：选一张 4000 万像素的图会卡住好几秒，
        // 系统判定无响应后直接把应用杀掉——用户看到的就是"选图片闪退"，
        // 而且"导入中…"根本来不及显示。
        scope.launch {
            // 任何环节失败都只当"导入失败"，绝不让异常冒到主线程（那会直接闪退）
            val name = try {
                withContext(Dispatchers.IO) {
                    AppearanceState.importImage(context.applicationContext, uri)
                }
            } catch (e: Throwable) {
                null
            }
            importing = false
            importError = name == null
            if (name != null) {
                runCatching { set { it.copy(background = Appearance.BG_IMAGE, imageFile = name) } }
            } else {
                // 失败时背景仍是原来那张（或纯色），下面的红色提示不会显示，必须弹 Toast
                Toast.makeText(context, "图片导入失败，请换一张试试", Toast.LENGTH_LONG).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        handlePicked(uri)
    }
    // 兜底选择器：部分精简 ROM / 鸿蒙没有系统图片选择器，PickVisualMedia 会抛
    // ActivityNotFoundException；此时退回 SAF 的 GetContent（同样不需要存储权限）。
    val fallbackPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        handlePicked(uri)
    }
    fun launchPicker() {
        // 标记「主动离开」：主界面的 onUserLeaveHint 会 finishAndRemoveTask()，
        // 不标记的话部分 ROM 会把"打开相册"当成用户退出，直接销毁任务与回调
        com.focusguard.app.util.LeaveGuard.markLeaving()
        com.focusguard.app.ui.theme.AppearanceState.markPickerOpened(context)
        runCatching {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            runCatching { fallbackPicker.launch("image/*") }.onFailure {
                com.focusguard.app.ui.theme.AppearanceState.clearPickerOpened(context)
                Toast.makeText(context, "当前系统没有可用的图片选择器", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppearancePreview(a)

        // ── 背景 ──
        SectionTitle(Icons.Default.Wallpaper, "背景")
        FxOptionRow(
            options = listOf(Appearance.BG_SOLID, Appearance.BG_GRADIENT, Appearance.BG_MESH, Appearance.BG_IMAGE),
            selected = a.background,
            label = {
                when (it) {
                    Appearance.BG_SOLID -> "纯色"
                    Appearance.BG_GRADIENT -> "渐变"
                    Appearance.BG_MESH -> "网格"
                    else -> "图片"
                }
            },
            onSelect = { bg ->
                if (bg == Appearance.BG_IMAGE && AppearanceState.imageFile(context, a) == null) {
                    launchPicker()
                } else {
                    set { it.copy(background = bg) }
                }
            }
        )

        // 配色预设：渐变背景与流光光晕共用一套色板，选完不用再分别调
        Label("配色预设")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Appearance.gradientNames.indices.forEach { style ->
                GradientChip(
                    style = style,
                    selected = a.gradientStyle == style,
                    onClick = { set { it.copy(gradientStyle = style) } }
                )
            }
        }

        FxExpand(visible = a.background == Appearance.BG_IMAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launchPicker() },
                    modifier = Modifier.weight(1f),
                    enabled = !importing
                ) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (importing) "导入中…" else "更换图片")
                }
            }
            if (importError) {
                Text("图片导入失败，请换一张试试", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            SliderRow(
                title = "遮罩",
                value = a.imageDim,
                range = 0f..90f,
                suffix = "%"
            ) { v -> set { it.copy(imageDim = v) } }
            Label("模糊")
            FxOptionRow(
                options = listOf(0, 10, 20, 40),
                selected = when {
                    a.imageBlur <= 0 -> 0
                    a.imageBlur <= 10 -> 10
                    a.imageBlur <= 20 -> 20
                    else -> 40
                },
                label = {
                    when (it) {
                        0 -> "关"
                        10 -> "弱"
                        20 -> "中"
                        else -> "强"
                    }
                },
                onSelect = { v -> set { it.copy(imageBlur = v) } }
            )
        }

        // ── 流光光晕 ──
        SectionTitle(Icons.Default.AutoAwesome, "流光")
        SwitchRow(
            title = "流光光晕",
            checked = a.glow
        ) { on -> set { it.copy(glow = on) } }
        FxExpand(visible = a.glow) {
            SliderRow(title = "光晕强度", value = a.glowIntensity, range = 10f..100f, suffix = "%") { v ->
                set { it.copy(glowIntensity = v) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        // ── 卡片 ──
        SectionTitle(Icons.Default.Layers, "卡片")
        FxOptionRow(
            options = listOf(
                Appearance.CARD_GLASS,
                Appearance.CARD_LIQUID,
                Appearance.CARD_SOLID,
                Appearance.CARD_OUTLINE
            ),
            selected = a.cardStyle,
            label = {
                when (it) {
                    Appearance.CARD_GLASS -> "玻璃"
                    Appearance.CARD_LIQUID -> "液态"
                    Appearance.CARD_SOLID -> "实色"
                    else -> "描边"
                }
            },
            onSelect = { v -> set { it.copy(cardStyle = v) } }
        )
        FxExpand(
            visible = a.cardStyle == Appearance.CARD_GLASS ||
                a.cardStyle == Appearance.CARD_LIQUID
        ) {
            SliderRow(title = "卡片不透明度", value = a.cardOpacity, range = 40f..100f, suffix = "%") { v ->
                set { it.copy(cardOpacity = v) }
            }
        }
        FxExpand(visible = a.cardStyle == Appearance.CARD_LIQUID) {
            SliderRow(
                title = "折射高度",
                value = a.liquidRefractionHeight,
                range = 8f..40f,
                suffix = "dp"
            ) { v -> set { it.copy(liquidRefractionHeight = v) } }
            SliderRow(
                title = "折射强度",
                value = a.liquidRefraction,
                range = 0f..100f,
                suffix = "%"
            ) { v -> set { it.copy(liquidRefraction = v) } }
        }
        SliderRow(
            title = "描边强度",
            value = a.cardBorder,
            range = 0f..100f,
            suffix = "%"
        ) { v -> set { it.copy(cardBorder = v) } }
        Label("圆角")
        FxOptionRow(
            options = listOf(Appearance.CORNER_SMALL, Appearance.CORNER_MEDIUM, Appearance.CORNER_LARGE),
            selected = a.corner,
            label = {
                when (it) {
                    Appearance.CORNER_SMALL -> "小"
                    Appearance.CORNER_LARGE -> "大"
                    else -> "中"
                }
            },
            onSelect = { v -> set { it.copy(corner = v) } }
        )

        // ── 文字 ──
        SectionTitle(Icons.Default.TextFields, "文字")
        SliderRow(title = "字号", value = a.fontScale, range = 85f..125f, suffix = "%", steps = 7) { v ->
            set { it.copy(fontScale = v) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        // ── 锁机页 ──
        SectionTitle(Icons.Default.Lock, "锁机页")
        Label("倒计时字体")
        FxOptionRow(
            options = listOf(Appearance.FONT_SERIF, Appearance.FONT_SANS, Appearance.FONT_MONO),
            selected = a.lockFont,
            label = {
                when (it) {
                    Appearance.FONT_SERIF -> "衬线"
                    Appearance.FONT_SANS -> "无衬线"
                    else -> "等宽"
                }
            },
            onSelect = { v -> set { it.copy(lockFont = v) } }
        )
        SwitchRow(title = "显示时钟与日期", checked = a.showClock) { on -> set { it.copy(showClock = on) } }
        SwitchRow(title = "显示箴言", checked = a.showMotto) { on -> set { it.copy(showMotto = on) } }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        SwitchRow(
            title = "界面动画",
            checked = a.motion
        ) { on -> set { it.copy(motion = on) } }

        TextButton(
            onClick = { set { Appearance(imageFile = it.imageFile) } },
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("恢复默认外观")
        }
    }
}

/**
 * 实时预览：背景 + 一张真实样式的卡片 + 字号。
 *
 * 卡片用的是全局 [inkCard]，因此卡片风格/不透明度/圆角/描边都会被如实反映；
 * 背景是图片时直接复用 [ImageBackground]，不会为了预览额外引入一套解码逻辑。
 */
@Composable
private fun AppearancePreview(a: Appearance) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > 0.5f
    val preset = Appearance.gradientPreset(a.gradientStyle, light)
    val brush = when {
        a.background == Appearance.BG_SOLID -> Brush.verticalGradient(listOf(scheme.background, scheme.background))
        preset != null && a.background != Appearance.BG_IMAGE -> Brush.verticalGradient(preset)
        else -> Brush.verticalGradient(
            listOf(
                lerp(scheme.background, scheme.primary, 0.10f),
                scheme.background,
                lerp(scheme.background, scheme.tertiary, 0.08f)
            )
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(brush)
    ) {
        if (a.background == Appearance.BG_IMAGE) {
            ImageBackground(a, scheme.background)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .inkCard()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "今日专注",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant
                        )
                        Text(
                            "25:00",
                            fontSize = (26 * a.fontScale / 100f).sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", fontSize = 12.sp, color = scheme.primary)
                    }
                }
            }
        }
    }
}

/** 渐变配色小样：色块 + 名称，选中加主色描边。 */
@Composable
private fun GradientChip(style: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > 0.5f
    val colors = Appearance.gradientPreset(style, light)
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 42.dp)
                .clip(shape)
                .then(
                    if (colors != null) Modifier.background(Brush.linearGradient(colors))
                    else Modifier.background(scheme.surfaceVariant)
                )
                .then(
                    if (selected) Modifier.border(2.dp, scheme.primary, shape) else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (colors == null) {
                Icon(Icons.Default.Palette, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            Appearance.gradientNames.getOrElse(style) { "" },
            fontSize = 10.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 滑杆：拖动时只更新本地值，松手才写入（避免每帧写 prefs 与全局重组）。 */
@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    hint: String? = null,
    steps: Int = 0,
    onCommit: (Int) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("${local.toInt()}$suffix", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = range,
            steps = steps
        )
        if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
