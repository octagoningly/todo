package com.lirui.oneline.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lirui.oneline.sync.ImportRules
import com.lirui.oneline.ui.theme.cardBackground

/**
 * ★ §86 作业导入的**预览确认**对话框。
 *
 * 为什么需要它（用户 2026-11-16 拍板"先弹预览让我确认，再写入"）：
 *
 * 1. **安全**：字段映射还没在真实使用中验证过。一旦映射有偏差（比如截止时间
 *    差一天），静默写入会污染用户已有的待办列表，还得一条条删。
 * 2. **认知**：用户需要知道"到底会加进来什么"，而不是事后翻列表才发现多了 4 条。
 * 3. **验证**：这是验证「科目/标题/绝对时间三字段」映射正确性的最直接方式。
 *
 * 只列**真的要写入的**（已按本学期 + 未过期过滤），并按截止时间升序 ——
 * 最紧急的在最上面。
 */
@Composable
fun HomeworkImportPreviewDialog(
    lines: List<ImportRules.PreviewLine>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入作业到待办") },
        text = {
            if (lines.isEmpty()) {
                Text(
                    "没有可导入的作业。\n\n" +
                        "可能原因：都已完成或已过期，或当前学期没有发布作业。"
                )
            } else {
                Column {
                    Text(
                        "共 ${lines.size} 条，确认后会加入待办并排好提醒：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    // 高度上限，条目多时可滚动，不把对话框撑破屏幕。
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(lines) { line ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 类型徽章：作业 / 小测 / 考查 / 考试
                                    Text(
                                        text = line.item.kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.padding(horizontal = 4.dp))
                                    // 截止时间放右侧，一眼看到"什么时候要交"
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "${line.dueDate} ${line.dueTime}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = line.todoTitle,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (lines.isNotEmpty()) {
                TextButton(onClick = onConfirm) { Text("确认导入") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (lines.isEmpty()) "知道了" else "取消")
            }
        },
        containerColor = MaterialTheme.colorScheme.cardBackground
    )
}
