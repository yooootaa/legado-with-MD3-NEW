package io.legado.app.ui.book.read.page.provider

import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.text.style.CharacterStyle
import android.util.Size
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import androidx.core.util.component1
import androidx.core.util.component2
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.noWordCountRegex
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewChar
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceChar
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceCharC
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceCharD
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.dpToPx

import io.legado.app.utils.fastSum
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.roundToInt

/**
 * 用于存储锚点ID的自定义Span
 */
private class AnchorSpan(val id: String) : CharacterStyle() {
    override fun updateDrawState(tp: android.text.TextPaint) {
        // 空实现，我们只是用这个Span来存储锚点ID信息
    }
}

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: ArrayList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
) {

    companion object {
        private val regexCache = mutableMapOf<String, Regex>()

        fun invalidateRegexCache() {
            regexCache.clear()
        }
    }

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val titleLineSpacingExtra = ChapterProvider.titleLineSpacingExtra
    private val titleLineSpacingSub = ChapterProvider.titleLineSpacingSub
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val adaptSpecialStyle = AppConfig.adaptSpecialStyle
    private val pageAnim = book.getPageAnim()
    private val titleSegType = ReadBookConfig.titleSegType
    private val titleSegDistance = ReadBookConfig.titleSegDistance
    private val titleSegFlag = ReadBookConfig.titleSegFlag
    private val titleSegScaling = ReadBookConfig.titleSegScaling
    private var pendingTextPage = TextPage()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)
    private val charWidthCache = mutableMapOf<Char, Float>()

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)


    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            launch {
                val bookSource = book.getBookSource() ?: return@launch
                BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
            }
            getTextChapter(book, bookChapter, displayTitle, bookContent)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun setProgressListener(l: LayoutProgressListener?) {
        try {
            if (isCompleted) {
                // no op
            } else if (exception != null) {
                l?.onLayoutException(exception!!)
            } else {
                listener = l
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    private fun onPageCompleted() {
        val textPage = pendingTextPage
        textPage.index = textPages.size
        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    /**
     * 获取拆分完的章节数据
     */
    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
    ) {
        val contents = bookContent.textList
        val imageStyle = book.getImageStyle()
        val isSingleImageStyle = imageStyle.equals(Book.imgStyleSingle, true)
        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        if (titleMode != 2 || bookChapter.isVolume || contents.isEmpty()) {
            val allTitleSegments = displayTitle.splitNotBlank("\n").flatMap { rawTitle ->
                TitleStyleParser.getSegments(
                    rawTitle,
                    titleSegType,
                    titleSegDistance,
                    titleSegFlag,
                    titleSegScaling
                )
            }

            allTitleSegments.forEachIndexed { index, segment ->
                val currentPaint: TextPaint
                val currentHeight: Float
                val currentMetrics: Paint.FontMetrics
                val lineIndexBefore = pendingTextPage.lines.size
                if (segment.isMainTitle) {
                    currentPaint = titlePaint
                    currentHeight = titlePaintTextHeight
                    currentMetrics = titlePaintFontMetrics
                } else {
                    currentPaint = TextPaint(titlePaint).apply {
                        textSize = titlePaint.textSize * segment.scale
                    }
                    currentMetrics = currentPaint.fontMetrics
                    currentHeight = currentMetrics.bottom - currentMetrics.top
                }

                val srcList = LinkedList<String>()
                val reviewImg = bookChapter.reviewImg
                var reviewTxt = ""
                if (index == allTitleSegments.lastIndex && reviewImg != null) {
                    srcList.add(reviewImg)
                    reviewTxt = if (reviewImg.contains("TEXT")) reviewChar else srcReplaceChar
                }

                setTypeText(
                    book = book,
                    text = segment.text + reviewTxt,
                    textPaint = currentPaint,
                    textHeight = currentHeight,
                    fontMetrics = currentMetrics,
                    imageStyle = imageStyle,
                    srcList = srcList.ifEmpty { null },
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume
                )

                if (segment.scale != 1.0f) {
                    val currentLines = pendingTextPage.lines
                    for (i in lineIndexBefore until currentLines.size) {
                        currentLines[i].titleTextSize = currentPaint.textSize
                    }
                }

                pendingTextPage.lines.last().isParagraphEnd = true
                stringBuilder.append("\n")

                if (index < allTitleSegments.lastIndex) {
                    durY += currentHeight * titleLineSpacingSub
                }
            }
            durY += titleBottomSpacing
            if (isSingleImageStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty()) {
                prepareNextPageIfNeed()
            }
        }

        val sb = StringBuffer()
        var isSetTypedImage = false
        var wordCount = 0
        contents.forEach { content ->
            currentCoroutineContext().ensureActive()
            val trimmedContent = content.trim()
            if (adaptSpecialStyle) {
                if (trimmedContent == "[newpage]") {
                    prepareNextPageIfNeed()
                    return@forEach
                } else if (trimmedContent.startsWith("<usehtml>")) {
                    setTypeHtml(imageStyle, book, trimmedContent.substring(9, trimmedContent.lastIndexOf("<")))
                    return@forEach
                }
            }
            
            // 自动检测是否包含 HTML 标签（如 <a> 或带 id 的标签），如果是则使用 HTML 排版引擎
            if (content.contains("<") && (book.isEpub || content.contains("id=", ignoreCase = true) || content.contains("data-anchor-id=", ignoreCase = true))) {
                setTypeHtml(imageStyle, book, content)
                pendingTextPage.lines.last().isParagraphEnd = true
                stringBuilder.append("\n")
                return@forEach
            }

            var text = content.replace(srcReplaceCharC, srcReplaceCharD)
            if (isTextImageStyle) {
                //图片样式为文字嵌入类型
                val srcList = LinkedList<String>()
                sb.setLength(0)
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let { src ->
                        srcList.add(src)
                        matcher.appendReplacement(sb, srcReplaceChar)
                    }
                }
                matcher.appendTail(sb)
                text = sb.toString()
                wordCount += text.replace(noWordCountRegex,"").length
                setTypeText(
                    book,
                    text,
                    contentPaint,
                    contentPaintTextHeight,
                    contentPaintFontMetrics,
                    imageStyle,
                    srcList = srcList
                )
            } else {
                if (isSingleImageStyle && isSetTypedImage) {
                    isSetTypedImage = false
                    prepareNextPageIfNeed()
                }
                var start = 0
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                sb.setLength(0)
                var isFirstLine = true
                if (content.contains("<img")) {
                    val matcher = AppPattern.imgPattern.matcher(text)
                    while (matcher.find()) {
                        currentCoroutineContext().ensureActive()
                        val imgSrc = matcher.group(1)!!
                        var iStyle: String? = null
                        var click: String? = null
                        var imgSize = ImageProvider.getImageSize(book, imgSrc, ReadBook.bookSource)
                        val urlMatcher = paramPattern.matcher(imgSrc)
                        if (urlMatcher.find()) {
                            var width: String? = null
                            val urlOptionStr = imgSrc.substring(urlMatcher.end())
                            GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
                                ?.let { map ->
                                    map.forEach { (key, value) ->
                                        when (key) {
                                            "style" -> iStyle = value
                                            "width" -> width = value
                                            "click" -> click = value
                                        }
                                    }
                                }
                            width?.let {
                                if (it.endsWith("%")) {
                                    it.dropLast(1).toIntOrNull()?.let { percentage ->
                                        val imgWidth = visibleWidth * percentage / 100
                                        val newHeight = imgSize.height * imgWidth / imgSize.width
                                        imgSize = Size(imgWidth, newHeight)
                                    }
                                } else {
                                    it.toIntOrNull()?.let { w ->
                                        val newHeight = imgSize.height * w / imgSize.width
                                        imgSize = Size(w, newHeight)
                                    }
                                }
                            }
                        }
                        if (iStyle == null) {
                            iStyle =
                                if (imgSize.width < 80 && imgSize.height < 80) "text" else imageStyle
                        }

                        if (start < matcher.start()) {
                            sb.append(text.substring(start, matcher.start()))
                        }
                        if (iStyle == "text" || iStyle == "TEXT") {
                            sb.append(if (iStyle == "TEXT") reviewChar else srcReplaceChar)
                            srcList.add(imgSrc)
                            clickList.add(click)
                        } else {
                            val textBefore = sb.toString()
                            if (textBefore.isNotBlank()) {
                                wordCount += textBefore.replace(noWordCountRegex,"").length
                                setTypeText(
                                    book, sb.toString(), contentPaint, contentPaintTextHeight,
                                    contentPaintFontMetrics, "TEXT", isFirstLine = isFirstLine,
                                    srcList = srcList, clickList = clickList
                                )
                                sb.setLength(0)
                                isFirstLine = false
                            }
                            setTypeImage(
                                book,
                                imgSrc,
                                contentPaintTextHeight,
                                iStyle,
                                imgSize,
                                click
                            ) // 传递点击信息
                            isSetTypedImage = true
                        }
                        start = matcher.end()
                    }
                }
                if (start < content.length) {
                    if (isSingleImageStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        prepareNextPageIfNeed()
                    }
                    val textAfter = content.substring(start, content.length)
                    sb.append(textAfter)
                }
                text = sb.toString()
                if (text.isNotBlank()) {
                    wordCount += text.replace(noWordCountRegex,"").length
                    setTypeText(
                        book,
                        if (AppConfig.enableReview) text + reviewChar else text,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        "TEXT",
                        isFirstLine = isFirstLine,
                        srcList = srcList.ifEmpty { null },
                        clickList = clickList.ifEmpty { null }
                    )
                }
            }
            pendingTextPage.lines.last().isParagraphEnd = true
            stringBuilder.append("\n")
        }
        val chapterWordCount = StringUtils.wordCountFormat(wordCount.toString())
        bookChapter.wordCount = chapterWordCount
        appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, chapterWordCount)
        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        currentCoroutineContext().ensureActive()
        onPageCompleted()
        onCompleted()
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        textHeight: Float,
        imageStyle: String?,
        size: Size,
        click: String? = null,
        linkUrl: String? = null,
        anchorId: String? = null
    ) {
        if (size.width > 0 && size.height > 0) {
            prepareNextPageIfNeed(durY)
            var height = size.height
            var width = size.width
            when (imageStyle?.uppercase()) {
                Book.imgStyleFull -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        prepareNextPageIfNeed(durY + height)
                    }
                }

                Book.imgStyleSingle -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    if (durY > 0f) {
                        prepareNextPageIfNeed()
                    }

                    // 图片竖直方向居中：调整 Y 坐标
                    if (height < visibleHeight) {
                        val adjustHeight = (visibleHeight - height) / 2f
                        durY = adjustHeight // 将 Y 坐标设置为居中位置
                    }
                }

                else -> {
                    if (size.width > visibleWidth) {
                        height = size.height * visibleWidth / size.width
                        width = visibleWidth
                    }
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    prepareNextPageIfNeed(durY + height)
                }
            }
            val textLine = TextLine(isImage = true)
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += height
            textLine.lineBottom = durY + paddingTop
            val (start, end) = if (visibleWidth > width) {
                when (imageStyle?.uppercase()) {
                    "RIGHT" -> Pair(visibleWidth - width, visibleWidth)
                    "LEFT" -> Pair(0f, width)
                    else -> {
                        val adjustWidth = (visibleWidth - width) / 2f
                        Pair(adjustWidth, adjustWidth + width)
                    }
                }
            } else {
                Pair(0f, width)
            }
            textLine.addColumn(
                ImageColumn(
                    start = absStartX + start.toFloat(), 
                    end = absStartX + end.toFloat(), 
                    src = src, 
                    click = click, 
                    linkUrl = linkUrl, 
                    anchorId = anchorId
                )
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ") // 确保翻页时索引计算正确
            pendingTextPage.addLine(textLine)
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private suspend fun setTypeHtml(
        imageStyle: String?,
        book: Book,
        htmlContent: String,
    ) {
        // 1. 预处理：注入SID/EID标记
        val processedHtml = preprocessAnchorIds(htmlContent)

        // 2. 解析HTML
        val spanned = processedHtml.parseAsHtml(HtmlCompat.FROM_HTML_MODE_LEGACY)
        val ssb = SpannableStringBuilder(spanned)

        // 去除首尾多余换行，避免产生空白页
        while (ssb.isNotEmpty() && ssb[0] == '\n') ssb.delete(0, 1)
        while (ssb.isNotEmpty() && ssb[ssb.length - 1] == '\n') ssb.delete(ssb.length - 1, ssb.length)

        // 去除多余的连续换行，限制最大连续换行为2个
        var k = 0
        while (k < ssb.length - 2) {
            if (ssb[k] == '\n' && ssb[k + 1] == '\n' && ssb[k + 2] == '\n') {
                ssb.delete(k + 2, k + 3)
            } else {
                k++
            }
        }

        // 3. 处理锚点标记
        val sidRegex = "\\{\\{SID:(.*?)\\}\\}".toRegex()
        while (true) {
            val match = sidRegex.find(ssb) ?: break
            val id = match.groupValues[1]
            val start = match.range.first
            ssb.delete(match.range.first, match.range.last + 1)

            val eidPattern = "\\{\\{EID:${Regex.escape(id)}\\}\\}".toRegex()
            val endMatch = eidPattern.find(ssb, start)
            if (endMatch != null) {
                val end = endMatch.range.first
                ssb.delete(endMatch.range.first, endMatch.range.last + 1)
                if (end > start) {
                    ssb.setSpan(AnchorSpan(id), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    ssb.insert(start, "\u200B")
                    ssb.setSpan(AnchorSpan(id), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                ssb.insert(start, "\u200B")
                ssb.setSpan(AnchorSpan(id), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            AppLog.putDebug("setTypeHtml: applied AnchorSpan for $id")
        }

        // 清理残留标记
        val cleanupRegex = "\\{\\{EID:.*?\\}\\}".toRegex()
        while (true) {
            val match = cleanupRegex.find(ssb) ?: break
            ssb.delete(match.range.first, match.range.last + 1)
        }

        val width = visibleWidth
        val textPaint = contentPaint
        val textColor = ReadBookConfig.textColor
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        
        val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StaticLayout.Builder.obtain(ssb, 0, ssb.length, textPaint, width)
                .setLineSpacing(paragraphSpacing.toFloat(), lineSpacingExtra)
                .setIncludePad(true)
                .setUseLineSpacingFromFallbacks(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                ssb,
                textPaint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                lineSpacingExtra,
                paragraphSpacing.toFloat(),
                true
            )
        }
        
        val tempPaint = TextPaint(textPaint)
        for (lineIndex in 0 until staticLayout.lineCount) {
            val lineStart = staticLayout.getLineStart(lineIndex)
            val lineEnd = staticLayout.getLineEnd(lineIndex)
            if (lineStart == lineEnd) continue
            
            val textLine = TextLine(isHtml = true)
            val lineText = StringBuilder()
            val lineLeft = staticLayout.getLineLeft(lineIndex)
            textLine.startX = absStartX + lineLeft
            val mLineTop = staticLayout.getLineTop(lineIndex).toFloat()
            val mLineBottom = staticLayout.getLineBottom(lineIndex).toFloat()
            val lineHeight = mLineBottom - mLineTop
            prepareNextPageIfNeed(durY + lineHeight)
            textLine.upTopBottom(durY, lineHeight, textPaint.fontMetrics)

            val columns = mutableListOf<BaseColumn>()
            var charIndex = lineStart
            while (charIndex < lineEnd) {
                val char = ssb[charIndex].toString()
                lineText.append(char)
                if (char == "\n") {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f
                    charIndex++
                    continue
                }
                
                val charX = staticLayout.getPrimaryHorizontal(charIndex)
                val textSize = extractTextSize(ssb, charIndex, textPaint.textSize)
                val textColor = extractTextColor(ssb, charIndex)
                val linkUrl = extractLinkUrl(ssb, charIndex)
                val anchorId = extractAnchorId(ssb, charIndex)
                
                val charRight = if (charIndex + 1 < lineEnd) {
                    staticLayout.getPrimaryHorizontal(charIndex + 1)
                } else {
                    tempPaint.textSize = textSize
                    charX + tempPaint.measureText(char)
                }
                
                var addedImage = false
                ssb.getSpans(charIndex, charIndex + 1, ImageSpan::class.java).firstOrNull()?.let { span ->
                    val source = span.source ?: return@let
                    val urlMatcher = paramPattern.matcher(source)
                    var iStyle: String? = null
                    var click: String? = null
                    var imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                    
                    if (urlMatcher.find()) {
                        val urlOptionStr = source.substring(urlMatcher.end())
                        GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()?.let { map ->
                            iStyle = map["style"]
                            click = map["click"]
                            map["width"]?.let { w ->
                                if (w.endsWith("%")) {
                                    w.dropLast(1).toIntOrNull()?.let { p ->
                                        val imgWidth = visibleWidth * p / 100
                                        imgSize = Size(imgWidth, imgSize.height * imgWidth / imgSize.width)
                                    }
                                } else {
                                    w.toIntOrNull()?.let { width ->
                                        imgSize = Size(width, imgSize.height * width / imgSize.width)
                                    }
                                }
                            }
                        }
                    }
                    
                    if (iStyle == null) {
                        iStyle = if (imgSize.width < 80 && imgSize.height < 80) "text" else imageStyle
                    }
                    
                    if (iStyle?.uppercase() == "TEXT") {
                        ImageProvider.cacheImage(book, source, ReadBook.bookSource)
                        columns.add(ImageColumn(absStartX + charX, absStartX + charRight, source, click, linkUrl, anchorId))
                    } else {
                        setTypeImage(book, source, contentPaintTextHeight, iStyle, imgSize, click, linkUrl, anchorId)
                    }
//                    System.out.println(linkUrl)
//                    System.out.println(anchorId)
//                    System.out.println(source)
                    addedImage = true
                }
                
                if (!addedImage) {
                    columns.add(TextHtmlColumn(absStartX + charX, absStartX + charRight, char, textSize, textColor, linkUrl, anchorId))
//                    System.out.println(linkUrl)
//                    System.out.println(anchorId)
//                    System.out.println(char)
                }
                charIndex++
            }
            
            textLine.text = lineText.toString()
            if (charIndex == lineEnd && lineIndex == staticLayout.lineCount - 1 && !textLine.isParagraphEnd) {
                textLine.isParagraphEnd = true
                durY += lineHeight * paragraphSpacing / 10f
            }
            
            if (textFullJustify && !textLine.isParagraphEnd) {
                justifyHtmlLine(columns, textLine, visibleWidth)
            } else {
                textLine.addColumns(columns)
            }
            
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            pendingTextPage.addLine(textLine)
            durY += lineHeight * lineSpacingExtra
            if (pendingTextPage.height < durY) {
                pendingTextPage.height = durY
            }
        }
    }

    private fun preprocessAnchorIds(htmlContent: String): String {
        return try {
            val doc = org.jsoup.Jsoup.parseBodyFragment(htmlContent)
            doc.outputSettings().prettyPrint(false)
            
            // 处理所有带ID的元素
            doc.select("[id], [data-anchor-id]").forEach { el ->
                val id = if (el.hasAttr("data-anchor-id")) el.attr("data-anchor-id") else el.id()
                if (id.isNotEmpty()) {
                    if (el.tag().name == "li" || el.tag().name == "a") {
                        el.prepend("{{SID:$id}}")
                        el.append("{{EID:$id}}")
                    } else {
                        el.before("{{SID:$id}}")
                        el.after("{{EID:$id}}")
                    }
                }
            }
            doc.body().html()
        } catch (e: Exception) {
            htmlContent
        }
    }


    private fun getTextWidths(text: String, textPaint: TextPaint, widthsArray: FloatArray) {
        if (textPaint == contentPaint) {
            for (i in text.indices) {
                val char = text[i]
                widthsArray[i] = charWidthCache.getOrPut(char) {
                    textPaint.measureText(char.toString())
                }
            }
        } else {
            textPaint.getTextWidthsCompat(text, widthsArray)
        }
    }

    /**
     * 排版文字
     */
    @Suppress("DEPRECATION")
    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        srcList: LinkedList<String>? = null,
        clickList: LinkedList<String?>? = null
    ) {
        val widthsArray = allocateFloatArray(text.length)
        getTextWidths(text, textPaint, widthsArray)
        val colorMap = applyRegexColorRules(text)
        val layout = if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(text, textPaint, visibleWidth, words, widths, indentSize)
        } else {
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        durY = when {
            //标题y轴居中
            emptyContent && textPages.isEmpty() -> {
                val textPage = pendingTextPage
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> {
                when (imageStyle?.uppercase()) {
                    Book.imgStyleSingle -> {
                        val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                        if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                    }

                    else -> durY + titleTopSpacing
                }
            }

            else -> durY
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            prepareNextPageIfNeed(durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            val desiredWidth = widths.fastSum()
            textLine.text = lineText
            val lineWordStyles = if (colorMap != null) {
                buildWordStyles(words, lineText, colorMap.colorArray, colorMap.fontPathArray, lineStart)
            } else null
            when (lineIndex) {
                0 if layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    //多行的第一行 非标题
                    addCharsToLineFirst(
                        book, absStartX, textLine, words, textPaint,
                        desiredWidth, widths, srcList, clickList, lineWordStyles
                    )
                }

                layout.lineCount - 1 -> {
                    //最后一行、单行
                    //标题x轴居中
                    val startX = if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else {
                        0f
                    }
                    addCharsToLineNatural(
                        book, absStartX, textLine, words,
                        startX, !isTitle && lineIndex == 0, widths, srcList, clickList, lineWordStyles
                    )
                }
                else -> {
                    if (
                        isTitle &&
                        (isMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        //标题居中
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            startX, false, widths, srcList, clickList, lineWordStyles
                        )
                    } else {
                        //中间行
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths, srcList, clickList, lineWordStyles
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += textHeight * if (isTitle) titleLineSpacingExtra else lineSpacingExtra
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.lastOrNull()?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    /**
     * 有缩进,两端对齐
     */
    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        wordStyles: List<WordStyle>? = null
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                x, true, textWidths, srcList, clickList, wordStyles
            )
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = ChapterProvider.indentChar,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            val wordStyles1 = wordStyles?.subList(bodyIndent.length, wordStyles.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1, textPaint,
                desiredWidth, x, textWidths1, srcList, clickList, wordStyles1
            )
        }
    }

    /**
     * 无缩进,两端对齐
     */
    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        wordStyles: List<WordStyle>? = null
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, srcList, clickList, wordStyles
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else {
                    (x + cw)
                }
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList, clickList,
                    wordStyles?.getOrNull(index)?.color,
                    wordStyles?.getOrNull(index)?.fontPath
                )
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            textLine.extraLetterSpacingOffsetX = -d / 2
            textLine.extraLetterSpacing = d / textPaint.textSize
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList, clickList,
                    wordStyles?.getOrNull(index)?.color,
                    wordStyles?.getOrNull(index)?.fontPath
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 自然排列
     */
    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        wordStyles: List<WordStyle>? = null
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            addCharToLine(
                book,
                absStartX,
                textLine,
                char,
                x,
                x1,
                index + 1 == words.size,
                srcList,
                clickList,
                wordStyles?.getOrNull(index)?.color,
                wordStyles?.getOrNull(index)?.fontPath
            )
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 添加字符
     */
    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        color: Int? = null,
        fontPath: String? = null
    ) {
        val column = when {
            !srcList.isNullOrEmpty() && (char == srcReplaceChar || char == reviewChar) -> {
                val src = srcList.removeFirst()
                val click = clickList?.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src,
                    click = click
                )
            }

            else -> {
                TextColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    charData = char,
                    color = color,
                    fontPath = fontPath
                )
            }
        }
        textLine.addColumn(column)
    }

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--
            offset++
            columns[columns.lastIndex - 1]
        } else {
            columns.last()
        }
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            val textPage = pendingTextPage
            // 双页的 durY 不正确，可能会小于实际高度
            if (textPage.height < durY) {
                textPage.height = durY
            }
            if (doublePage && absStartX < viewWidth / 2) {
                //当前页面左列结束
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                //当前页面结束,设置各种值
                if (textPage.leftLineSize == 0) {
                    textPage.leftLineSize = textPage.lineSize
                }
                textPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive()
                onPageCompleted()
                //新建页面
                pendingTextPage = TextPage()
                stringBuilder.clear()
                absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) {
            floatArray = FloatArray(size)
        }
        return floatArray
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

    private data class RegexMatchResult(
        val colorArray: IntArray,
        val fontPathArray: Array<String?>
    )

    private fun applyRegexColorRules(text: String): RegexMatchResult? {
        val rules = ReadBookConfig.regexColorRules
        if (rules.isEmpty()) return null
        var hasMatch = false
        for (rule in rules) {
            try {
                val regex = regexCache.getOrPut(rule.pattern) { Regex(rule.pattern) }
                if (regex.containsMatchIn(text)) {
                    hasMatch = true
                    break
                }
            } catch (_: Exception) {
            }
        }
        if (!hasMatch) return null
        val colorArray = IntArray(text.length) { -1 }
        val fontPathArray = arrayOfNulls<String>(text.length)
        for (rule in rules) {
            try {
                val regex = regexCache.getOrPut(rule.pattern) { Regex(rule.pattern) }
                val matches = regex.findAll(text)
                for (match in matches) {
                    for (i in match.range) {
                        colorArray[i] = rule.color
                        if (rule.fontPath.isNotEmpty()) {
                            fontPathArray[i] = rule.fontPath
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return RegexMatchResult(colorArray, fontPathArray)
    }

    private data class WordStyle(
        val color: Int?,
        val fontPath: String?
    )

    private fun buildWordStyles(
        words: List<String>,
        lineText: String,
        colorArray: IntArray,
        fontPathArray: Array<String?>,
        lineStart: Int
    ): List<WordStyle> {
        val wordStyles = mutableListOf<WordStyle>()
        var charOffset = 0
        for (word in words) {
            val wordLen = word.length
            var color: Int? = null
            var fontPath: String? = null
            for (j in 0 until wordLen) {
                val idx = lineStart + charOffset + j
                if (color == null && colorArray[idx] != -1) {
                    color = colorArray[idx]
                }
                if (fontPath == null && fontPathArray[idx] != null) {
                    fontPath = fontPathArray[idx]
                }
                if (color != null && fontPath != null) break
            }
            wordStyles.add(WordStyle(color, fontPath))
            charOffset += wordLen
        }
        return wordStyles
    }

    private fun extractTextSize(spanned: Spanned, index: Int, defaultSize: Float): Float {
        val spans = spanned.getSpans(index, index + 1, RelativeSizeSpan::class.java)
        return spans.lastOrNull()?.let { defaultSize * it.sizeChange } ?: defaultSize
    }

    private fun extractTextColor(spanned: Spanned, index: Int): Int? {
        return spanned.getSpans(index, index + 1, ForegroundColorSpan::class.java)
            .lastOrNull()?.foregroundColor
    }

    private fun extractLinkUrl(spanned: Spanned, index: Int): String? {
        val spans = spanned.getSpans(index, index + 1, URLSpan::class.java)
        for (span in spans.reversed()) {
            if (!span.url.isNullOrEmpty()) return span.url
        }
        
        val clickableSpans = spanned.getSpans(index, index + 1, android.text.style.ClickableSpan::class.java)
        for (span in clickableSpans.reversed()) {
            if (span is URLSpan && !span.url.isNullOrEmpty()) return span.url
        }
        return null
    }

    private fun extractAnchorId(spanned: Spanned, index: Int): String? {
        val spans = spanned.getSpans(index, index + 1, AnchorSpan::class.java)
        return spans.lastOrNull()?.id
    }

    private fun justifyHtmlLine(
        columns: MutableList<BaseColumn>,
        textLine: TextLine,
        lineWidth: Int
    ) {
        if (columns.isEmpty()) return
        val firstCol = columns.first()
        val lastCol = columns.last()
        val currentWidth = lastCol.end - firstCol.start
        val residualWidth = lineWidth - currentWidth

        if (residualWidth <= 0) {
            textLine.addColumns(columns)
            return
        }

        val spaceCount = columns.count {
            (it as? TextBaseColumn)?.charData == " "
        }

        if (spaceCount > 1) {
            val spaceIncrement = residualWidth / spaceCount
            textLine.wordSpacing = spaceIncrement
            var currentX = columns[0].start
            for (i in columns.indices) {
                val col = columns[i]
                val width = col.end - col.start
                if ((col as? TextBaseColumn)?.charData == " " && i != columns.lastIndex) {
                    col.start = currentX
                    col.end = currentX + width + spaceIncrement
                    currentX = col.start
                } else {
                    col.start = currentX
                    col.end = currentX + width
                    currentX = col.start
                }
                textLine.addColumn(col)
            }
        } else {
            val gapCount = columns.lastIndex
            if (gapCount > 0) {
                val charIncrement = residualWidth / gapCount
                var currentX = columns[0].start
                for (i in columns.indices) {
                    val col = columns[i]
                    val width = col.end - col.start
                    if (i != columns.lastIndex) {
                        col.start = currentX
                        col.end = currentX + width + charIncrement
                        currentX = col.end
                    } else {
                        col.start = currentX
                        col.end = currentX + width
                    }
                    textLine.addColumn(col)
                }
            } else {
                textLine.addColumns(columns)
            }
        }
    }
}
