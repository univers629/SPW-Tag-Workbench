// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.univers629.spwtagworkbench.data.Repo
import io.github.univers629.spwtagworkbench.data.Status

/**
 * 全局操作状态提示：贴在空间底部中间的一枚胶囊。
 *
 * 桌面与所有专题页共用同一条提示 —— 「写入草稿」「保存」「匹配」这类动作
 * 分布在多个页面里，提示不能只出现在桌面。
 */
@Composable
fun StatusToast(modifier: Modifier = Modifier) {
    val pal = Salt
    val status = Repo.status ?: return
    val color =
        when (status.kind) {
            Status.Kind.Ok -> pal.ok
            Status.Kind.Warn -> pal.warn
            Status.Kind.Error -> pal.error
            Status.Kind.Busy -> pal.accent
        }
    Card(
        modifier = modifier.clickable { Repo.clearStatus() },
        color = pal.cardOverlay,
        radius = 10.dp,
        outline = color.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
            )
            Spacer(Modifier.width(9.dp))
            Txt(status.text, size = T.caption, color = pal.text, maxLines = 1)
        }
    }
}
