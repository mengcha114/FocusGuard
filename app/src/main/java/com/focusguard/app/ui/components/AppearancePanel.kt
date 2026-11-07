package com.focusguard.app.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.ui.theme.Appearance
import com.focusguard.app.ui.theme.AppearanceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个性化外观面板：背景、光晕、卡片、圆角、字号、锁机页元素、动画。
 * 所有修改立即写入 [AppearanceState]，全应用（含锁机页）即时生效。
 */
@Composable
fun AppearancePanel() {
    val context = LocalContext.current
    val a = AppearanceState.get(context)
    fun set(transform: (Appearance) -> Appearance) = AppearanceState.update(context, transform)

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
    // 相册选择器的启动也可能失败：部分机型/精简 ROM 既没有系统相册选择器，
    // 也没有可用的文档选择器，launch() 会直接抛 ActivityNotFoundException。
    // 不接住就是"点选图片就闪退"。
    fun launchPicker() {
        runCatching {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            Toast.makeText(context, "当前系统没有可用的图片选择器", Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ── 背景 ──
        Label("背景")
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
                suffix = "%",
                hint = "调高可提升图片上的文字可读性"
            ) { v -> set { it.copy(imageDim = v) } }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SliderRow(title = "模糊", value = a.imageBlur, range = 0f..40f, suffix = "dp") { v ->
                    set { it.copy(imageBlur = v) }
                }
            }
        }

        // ── 流光光晕 ──
        SwitchRow(
            title = "流光光晕",
            hint = "页面背景两团柔和光晕，颜色跟随主题",
            checked = a.glow
        ) { on -> set { it.copy(glow = on) } }
        FxExpand(visible = a.glow) {
            SliderRow(title = "光晕强度", value = a.glowIntensity, range = 10f..100f, suffix = "%") { v ->
                set { it.copy(glowIntensity = v) }
            }
            SwitchRow(title = "光晕缓慢漂移", checked = a.glowMotion) { on -> set { it.copy(glowMotion = on) } }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        // ── 卡片 ──
        Label("卡片")
        FxOptionRow(
            options = listOf(Appearance.CARD_GLASS, Appearance.CARD_SOLID, Appearance.CARD_OUTLINE),
            selected = a.cardStyle,
            label = {
                when (it) {
                    Appearance.CARD_GLASS -> "玻璃"
                    Appearance.CARD_SOLID -> "实色"
                    else -> "描边"
                }
            },
            onSelect = { v -> set { it.copy(cardStyle = v) } }
        )
        FxExpand(visible = a.cardStyle == Appearance.CARD_GLASS) {
            SliderRow(title = "卡片不透明度", value = a.cardOpacity, range = 40f..100f, suffix = "%") { v ->
                set { it.copy(cardOpacity = v) }
            }
        }
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
        SliderRow(title = "字号", value = a.fontScale, range = 85f..125f, suffix = "%", steps = 7) { v ->
            set { it.copy(fontScale = v) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        // ── 锁机页 ──
        Label("锁机页")
        Text("倒计时字体", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            hint = "入场、按压回弹与数字滚动；关闭后更省电",
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
