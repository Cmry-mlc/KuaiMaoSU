package com.kuaimaosu.manager.ui.screen.susfs.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuaimaosu.manager.ui.component.settings.SettingsJumpPageWidget
import com.kuaimaosu.manager.ui.component.settings.lazySegmentColumn
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsAddTile
import com.kuaimaosu.manager.ui.screen.susfs.component.SusfsPillButton
import com.kuaimaosu.manager.ui.theme.KuaiMaoColors
import com.kuaimaosu.manager.ui.theme.KuaiMaoDimens

private const val ADD_ENTRY_KEY = "susfs_add_entry"
private const val EMPTY_STATE_KEY = "susfs_empty_state"
private const val ENTRY_KEY_PREFIX = "susfs_entry:"

private sealed interface SuSFSEntryRow<out T> {
    data object Add : SuSFSEntryRow<Nothing>

    data class Entry<T>(val value: T) : SuSFSEntryRow<T>
}

/**
 * 每页顶部的说明卡。
 *
 * 原来用的是 primaryContainer 色块（浅蓝底 + 大标题），在深色主题里像一张
 * 未完成的占位；一页里出现两三次还会抢走列表的注意力。
 * 现在改成与其它卡同底的白卡，标题前一个品牌色小点，正文压低一档。
 */
@Composable
internal fun SuSFSDescriptionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(KuaiMaoDimens.rCard)
            .background(KuaiMaoColors.canvasCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(KuaiMaoColors.brand),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = title,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
                color = KuaiMaoColors.textSec,
            )
        }
        description?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = KuaiMaoColors.textMuted,
            )
        }
        content()
    }
}

/**
 * 条目列表：添加块置顶、条目收进一张卡、空态给引导。
 *
 * 旧实现里「添加」和普通条目长得一样，空列表则是一个占半屏的灰盒子。
 */
internal fun <T> LazyListScope.susfsEntryList(
    entries: List<T>,
    addEntryTitle: String,
    emptyTitle: String,
    emptyDescription: String,
    entryKey: (T) -> Any,
    onAddEntry: () -> Unit,
    entryContent: @Composable LazyItemScope.(T) -> Unit,
) {
    item(key = ADD_ENTRY_KEY) {
        SusfsAddTile(
            text = addEntryTitle,
            icon = Icons.TwoTone.Add,
            onClick = onAddEntry,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    if (entries.isEmpty()) {
        item(key = EMPTY_STATE_KEY) {
            Column(
                modifier = Modifier
                    .fillParentMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(KuaiMaoColors.canvasSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = KuaiMaoColors.textDisabled,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = emptyTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = KuaiMaoColors.text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = emptyDescription,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = KuaiMaoColors.textMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                SusfsPillButton(
                    text = addEntryTitle,
                    icon = Icons.TwoTone.Add,
                    primary = true,
                    onClick = onAddEntry,
                )
            }
        }
        return
    }

    item(key = ENTRY_KEY_PREFIX + "header") {
        Spacer(Modifier.height(14.dp))
    }

    lazySegmentColumn(
        items = entries,
        key = { _, value -> "$ENTRY_KEY_PREFIX${entryKey(value)}" },
        contentType = { _, _ -> "susfs_entry" },
    ) { _, value ->
        entryContent(value)
    }
}
