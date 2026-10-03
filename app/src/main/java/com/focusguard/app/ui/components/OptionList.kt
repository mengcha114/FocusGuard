package com.focusguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 选择题选项列表（答题页与验证弹窗共用）。
 *
 * 此前两处界面都只画 A/B/C/D 字母键、从不显示选项文字，而题库的题干里
 * 并不包含选项（选项在独立的 options 里）⇒ 用户只能盲猜。这里把选项文字
 * 完整列出来，整行可点：
 * - 单选：点一下即提交（与原来的字母键行为一致）
 * - 多选：点一下切换选中，选好后由调用方提供「提交答案」按钮
 */
@Composable
fun OptionList(
    options: List<String>,
    selected: String,
    multi: Boolean,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, raw ->
            val letter = ('A' + index).toString()
            val picked = multi && letter in selected
            // 题库里的选项通常已带「A. 」前缀，没有时补上，保证字母与文字一一对应
            val label = if (raw.trimStart().startsWith(letter)) raw.trim() else "$letter. $raw"
            Text(
                text = label,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = if (picked) selectedContentColor else contentColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (picked) selectedColor else containerColor)
                    .clickable(enabled = enabled) { onSelect(letter) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    }
}
