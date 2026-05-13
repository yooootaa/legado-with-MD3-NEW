package io.legado.app.ui.book.read.page.entities


import androidx.annotation.Keep
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.BookContent
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextChapterLayout
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.utils.fastBinarySearchBy
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.min
/**
 * 章节信息
 */
@Keep
@Suppress("unused")
data class TextChapter(
    val chapter: BookChapter,
    val position: Int,
    val title: String,
    val chaptersSize: Int,
    val sameTitleRemoved: Boolean,
    val isVip: Boolean,
    val isPay: Boolean,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?
) : LayoutProgressListener {

    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = textPages

    private var layout: TextChapterLayout? = null

    val layoutChannel get() = layout!!.channel

    fun getPage(index: Int): TextPage? {
        return pages.getOrNull(index)
    }

    fun getPageByReadPos(readPos: Int): TextPage? {
        return getPage(getPageIndexByCharIndex(readPos))
    }

    val lastPage: TextPage? get() = pages.lastOrNull()

    val lastIndex: Int get() = pages.lastIndex

    val lastReadLength: Int get() = getReadLength(lastIndex)

    val pageSize: Int get() = pages.size

    var listener: LayoutProgressListener? = null

    var isCompleted = false

    val paragraphs by lazy {
        paragraphsInternal
    }

    val pageParagraphs by lazy {
        pageParagraphsInternal
    }

    val paragraphsInternal: ArrayList<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            for (i in pages.indices) {
                val lines = pages[i].lines
                for (a in lines.indices) {
                    val line = lines[a]
                    if (line.paragraphNum <= 0) continue
                    if (paragraphs.lastIndex < line.paragraphNum - 1) {
                        paragraphs.add(TextParagraph(line.paragraphNum))
                    }
                    paragraphs[line.paragraphNum - 1].textLines.add(line)
                }
            }
            return paragraphs
        }

    val pageParagraphsInternal: List<TextParagraph>
        get() {
            val paragraphs = arrayListOf<TextParagraph>()
            for (i in pages.indices) {
                paragraphs.addAll(pages[i].paragraphs)
            }
            for (i in paragraphs.indices) {
                paragraphs[i].num = i + 1
            }
            return paragraphs
        }

    /**
     * @param index 页数
     * @return 是否是最后一页
     */
    fun isLastIndex(index: Int): Boolean {
        return isCompleted && index >= pages.size - 1
    }

    fun isLastIndexCurrent(index: Int): Boolean {
        return index >= pages.size - 1
    }

    /**
     * @param pageIndex 页数
     * @return 已读长度
     */
    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0) return 0
        return pages[min(pageIndex, lastIndex)].chapterPosition
        /*
        var length = 0
        val maxIndex = min(pageIndex, pages.size)
        for (index in 0 until maxIndex) {
            length += pages[index].charSize
        }
        return length
        */
    }

    /**
     * @param length 当前页面文字在章节中的位置
     * @return 下一页位置,如果没有下一页返回-1
     */
    fun getNextPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex + 1 >= pageSize) {
            return -1
        }
        return getReadLength(pageIndex + 1)
    }

    /**
     * @param length 当前页面文字在章节中的位置
     * @return 上一页位置,如果没有上一页返回-1
     */
    fun getPrevPageLength(length: Int): Int {
        val pageIndex = getPageIndexByCharIndex(length)
        if (pageIndex - 1 < 0) {
            return -1
        }
        return getReadLength(pageIndex - 1)
    }

    /**
     * 获取内容
     */
    fun getContent(): String {
        val stringBuilder = StringBuilder()
        pages.forEach {
            stringBuilder.append(it.text)
        }
        return stringBuilder.toString()
    }

    /**
     * @return 获取未读文字
     */
    fun getUnRead(pageIndex: Int): String {
        val stringBuilder = StringBuilder()
        if (pages.isNotEmpty()) {
            for (index in pageIndex..pages.lastIndex) {
                stringBuilder.append(pages[index].text)
            }
        }
        return stringBuilder.toString()
    }

    /**
     * @return 需要朗读的文本列表
     * @param pageIndex 起始页
     * @param pageSplit 是否分页
     * @param startPos 从当前页什么地方开始朗读
     */
    fun getNeedReadAloud(
        pageIndex: Int,
        pageSplit: Boolean,
        startPos: Int,
        pageEndIndex: Int = pages.lastIndex
    ): String {
        val stringBuilder = StringBuilder()
        if (pages.isNotEmpty()) {
            for (index in pageIndex..min(pageEndIndex, pages.lastIndex)) {
                stringBuilder.append(pages[index].text.replace(Regex("[袮꧁]"), " "))
                if (pageSplit && !stringBuilder.endsWith("\n")) {
                    stringBuilder.append("\n")
                }
            }
        }
        return stringBuilder.substring(startPos).toString()
    }

    fun getParagraphNum(
        position: Int,
        pageSplit: Boolean,
    ): Int {
        val paragraphs = getParagraphs(pageSplit)
        paragraphs.forEach { paragraph ->
            if (position in paragraph.chapterIndices) {
                return paragraph.num
            }
        }
        return -1
    }

    fun getParagraphs(pageSplit: Boolean): List<TextParagraph> {
        return if (pageSplit) {
            if (isCompleted) pageParagraphs else pageParagraphsInternal
        } else {
            if (isCompleted) paragraphs else paragraphsInternal
        }
    }

    fun getLastParagraphPosition(): Int {
        return pageParagraphs.last().chapterPosition
    }

    /**
     * @return 根据索引位置获取所在页
     */
    fun getPageIndexByCharIndex(charIndex: Int): Int {
        val pageSize = pages.size
        if (pageSize == 0) {
            return -1
        }
        val bIndex = pages.takeIf { it.isNotEmpty() }?.fastBinarySearchBy(charIndex, 0, pageSize) {
            it.chapterPosition
        } ?: 0
        val index = abs(bIndex + 1) - 1
        // 判断是否已经排版到 charIndex ，没有则返回 -1
        if (!isCompleted && index == pageSize - 1) {
            val page = pages[index]
            val pageEndPos = page.chapterPosition + page.charSize
            if (charIndex > pageEndPos) {
                return -1
            }
        }
        return index
        /*
        var length = 0
        for (i in pages.indices) {
            val page = pages[i]
            length += page.charSize
            if (length > charIndex) {
                return page.index
            }
        }
        return pages.lastIndex
        */
    }

    fun clearSearchResult() {
        for (i in pages.indices) {
            val page = pages[i]
            page.searchResult.forEach {
                it.selected = false
                it.isSearchResult = false
            }
            page.searchResult.clear()
        }
    }

    /**
     * 标记书签位置（根据书签内容长度划线）
     * @param bookmarks 书签列表
     */
    fun markBookmarks(bookmarks: List<io.legado.app.data.entities.Bookmark>, pages: List<TextPage>) {
        // 创建副本以避免并发修改异常
        val pagesCopy = ArrayList(pages)
        for (page in pagesCopy) {
            markPageBookmarks(bookmarks, page)
        }
    }

    /**
     * 极致优化的书签标记：Page级坐标对存储 + 二分查找定位
     */
    fun markPageBookmarks(bookmarks: List<io.legado.app.data.entities.Bookmark>, page: TextPage) {
        var changed = false

        // 1. 定向清理：根据记录的坐标对精准重置
        if (page.bookmarkRegions.isNotEmpty()) {
            for (region in page.bookmarkRegions) {
                for (l in region.startLine..region.endLine) {
                    val line = page.getLine(l)
                    val s = if (l == region.startLine) region.startCol else 0
                    val e = if (l == region.endLine) region.endCol else line.columns.lastIndex
                    for (c in s..e) {
                        val column = line.columns.getOrNull(c)
                        if (column is TextBaseColumn) {
                            column.isBookmark = false
                            column.bookmark = null
                        }
                    }
                    line.bookmarkColumnCount = 0
                }
            }
            page.bookmarkRegions.clear()
            changed = true
        }

        val pageStart = page.chapterPosition
        val pageEnd = pageStart + page.charSize - 1
        val relevantBookmarks = bookmarks.filter {
            val bEnd = it.chapterPos + it.bookText.length - 1
            it.chapterPos <= pageEnd && bEnd >= pageStart
        }

        if (relevantBookmarks.isEmpty()) {
            if (changed) page.invalidateAll()
            return
        }

        // 2. 标记新书签
        for (bookmark in relevantBookmarks) {
            val bStart = bookmark.chapterPos
            val bEnd = bStart + bookmark.bookText.length - 1

            // 修正：支持跨页定位，起始/结束位置如果不在本页，则取本页边界
            val startLoc = if (bStart < pageStart) 0 to 0 else findLineColumn(page, bStart)
            val endLoc = if (bEnd > pageEnd) {
                val lastLineIdx = page.lineSize - 1
                lastLineIdx to page.getLine(lastLineIdx).columns.lastIndex
            } else findLineColumn(page, bEnd)

            if (startLoc == null || endLoc == null) continue

            val region = TextPage.BookmarkRegion(
                startLoc.first, startLoc.second,
                endLoc.first, endLoc.second,
                bookmark
            )
            page.bookmarkRegions.add(region)

            // 更新 Column 状态（用于点击检测）并增加计数
            for (l in region.startLine..region.endLine) {
                val line = page.getLine(l)
                val s = if (l == region.startLine) region.startCol else 0
                val e = if (l == region.endLine) region.endCol else line.columns.lastIndex
                for (c in s..e) {
                    val column = line.columns.getOrNull(c)
                    if (column is TextBaseColumn) {
                        column.isBookmark = true
                        column.bookmark = bookmark
                        line.bookmarkColumnCount++
                    }
                }
                changed = true
            }
        }

        if (changed) page.invalidateAll()
    }

    // 内部辅助函数：二分查找 + 行内扫描定位坐标
    private fun findLineColumn(page: TextPage, targetPos: Int): Pair<Int, Int>? {
        // 1. 二分查找定位行
        var lIndex = page.lines.fastBinarySearchBy(targetPos) { it.chapterPosition }
        if (lIndex < 0) lIndex = -(lIndex + 1) - 1
        
        // 确保索引在有效范围内且目标位置在该行起始位置之后
        lIndex = lIndex.coerceIn(0, page.lineSize - 1)
        val line = page.lines[lIndex]
        
        // 如果找到的行起始位置已经超过目标位置，且不是第一行，则向前退一行
        if (line.chapterPosition > targetPos && lIndex > 0) {
            return findLineColumnInLine(page, lIndex - 1, targetPos)
        }

        return findLineColumnInLine(page, lIndex, targetPos)
    }

    private fun findLineColumnInLine(page: TextPage, lIndex: Int, targetPos: Int): Pair<Int, Int>? {
        val line = page.lines[lIndex]
        var curPos = line.chapterPosition
        for (cIndex in line.columns.indices) {
            val col = line.columns[cIndex]
            val len = if (col is TextBaseColumn) col.charData.length.coerceAtLeast(1) else 1
            if (targetPos >= curPos && targetPos < curPos + len) {
                return lIndex to cIndex
            }
            curPos += len
        }
        // 如果在行末（可能是段落结尾符），返回该行最后一列
        if (targetPos >= curPos && targetPos < line.chapterPosition + line.charSize + (if (line.isParagraphEnd) 1 else 0)) {
            return lIndex to line.columns.lastIndex
        }
        return null
    }
    fun createLayout(scope: CoroutineScope, book: Book, bookContent: BookContent) {
        if (layout != null) {
            throw IllegalStateException("已经排版过了")
        }
        layout = TextChapterLayout(
            scope,
            this,
            textPages,
            book,
            bookContent,
        )
    }

    fun setProgressListener(l: LayoutProgressListener?) {
        if (isCompleted) {
            // no op
        } else if (layout?.exception != null) {
            l?.onLayoutException(layout?.exception!!)
        } else {
            listener = l
        }
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        listener?.onLayoutPageCompleted(index, page)
    }

    override fun onLayoutCompleted() {
        isCompleted = true
        listener?.onLayoutCompleted()
        listener = null
    }

    override fun onLayoutException(e: Throwable) {
        listener?.onLayoutException(e)
        listener = null
    }

    fun cancelLayout() {
        layout?.cancel()
        listener = null
    }

    companion object {
        val emptyTextChapter = TextChapter(
            BookChapter(), -1, "emptyTextChapter", -1,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            null
        ).apply { isCompleted = true }
    }

}
