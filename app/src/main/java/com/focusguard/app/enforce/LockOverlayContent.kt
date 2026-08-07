package com.focusguard.app.enforce

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.app.data.LockState

/**
 * 覆盖层内容 UI（Compose）。
 *
 * 设计原则：
 * - 覆盖层只做"视觉封锁 + 一个跳转入口"，不内嵌答题（答题需要 IME，必须在 Activity 里）
 * - NOT_FOCUSABLE 标志使覆盖层不拦截触摸，因此这里的 Button 通过把焦点设回 Activity 间接生效
 * - 覆盖层在 Activity 之上：用户能看到锁机信息，但无法绕过
 */
@Composable
fun LockOverlayContent(
    lockState: LockState,
    onStartChallenge: () -> Unit
) {
    // 每秒刷新倒计时
    var remainingSeconds by remember { mutableIntStateOf(lockState.remainingSeconds) }
    var isPausing by remember { mutableStateOf(lockState.isPaused) }

    LaunchedEffect(Unit) {
        while (lockState.isLocked) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds = lockState.remainingSeconds
            isPausing = lockState.isPaused
        }
    }

    // 暂停期间隐藏覆盖层（让用户自由使用）
    AnimatedVisibility(
        visible = !isPausing,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 半透明黑底：足够遮挡小窗内容，但不影响答题界面的交互
                .background(Color(0xE8000000))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFF7C4DFF),
                    modifier = Modifier.size(48.dp)
                )

                Text(
                    text = "专注卫士",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                val h = remainingSeconds / 3600
                val m = (remainingSeconds % 3600) / 60
                val s = remainingSeconds % 60
                val timeText = if (h > 0) "%02d:%02d:%02d".format(h, m, s)
                else "%02d:%02d".format(m, s)

                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "剩余时间",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        Text(
                            text = timeText,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF6B6B)
                        )
                    }
                }

                Text(
                    text = "屏幕已锁定，请专心工作学习",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                OutlinedButton(
                    onClick = onStartChallenge,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("答题解锁", color = Color(0xFFD0BCFF), fontSize = 15.sp)
                }
            }
        }
    }
}
