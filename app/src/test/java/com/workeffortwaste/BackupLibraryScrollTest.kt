package com.workeffortwaste

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests when the backup library overflows its viewport and should remain scrollable. */
class BackupLibraryScrollTest {
    private fun layout(
        viewportHeight: Int = 500,
        itemSizes: List<Int> = listOf(100, 100),
        totalItemsCount: Int = itemSizes.size,
        firstOffset: Int = 0,
        spacing: Int = 0,
        topPadding: Int = 0,
        bottomPadding: Int = 112,
    ): LazyListLayoutInfo {
        var offset = firstOffset
        val items = itemSizes.mapIndexed { index, size ->
            val itemOffset = offset
            offset += size + spacing
            object : LazyListItemInfo {
                override val index = index
                override val key = index
                override val offset = itemOffset
                override val size = size
                override val contentType: Any? = null
            }
        }
        return object : LazyListLayoutInfo {
            override val visibleItemsInfo = items
            override val viewportStartOffset = -topPadding
            override val viewportEndOffset = viewportHeight - topPadding
            override val totalItemsCount = totalItemsCount
            override val viewportSize = IntSize(400, viewportHeight)
            override val beforeContentPadding = topPadding
            override val afterContentPadding = bottomPadding
            override val mainAxisItemSpacing = spacing
        }
    }

    @Test
    fun detectsOverflowIncludingSpacingAndMargins() {
        assertFalse(backupLibraryOverflows(layout(itemSizes = emptyList()), 0f))
        assertFalse(backupLibraryOverflows(layout(), 0f))
        assertFalse(backupLibraryOverflows(layout(itemSizes = listOf(388)), 0f))
        assertTrue(backupLibraryOverflows(layout(itemSizes = listOf(389)), 0f))
        val fitting = layout(itemSizes = listOf(180, 180), spacing = 4, topPadding = 24)
        val overflowing = layout(itemSizes = listOf(180, 181), spacing = 4, topPadding = 24)
        assertFalse(backupLibraryOverflows(fitting, 0f))
        assertTrue(backupLibraryOverflows(overflowing, 0f))
    }

    @Test
    fun scrollingAndHeaderCollapseDoNotChangeOverflowDecision() {
        assertTrue(backupLibraryOverflows(layout(totalItemsCount = 10), 0f))
        assertTrue(backupLibraryOverflows(layout(viewportHeight = 600, itemSizes = listOf(438)), -100f))
        assertFalse(backupLibraryOverflows(layout(viewportHeight = 600, firstOffset = -50), -100f))
    }
}
