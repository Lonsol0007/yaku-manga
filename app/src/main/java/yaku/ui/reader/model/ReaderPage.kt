package yaku.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /**
     * Set when the reader has been asked to translate this page.
     *
     * Translating a page costs tens of seconds, and it used to run inside the load of every
     * page behind a single lock, so opening a chapter meant waiting for each page in turn
     * before any of it could be read. It is now asked for one page at a time; the holder
     * watches this and re-renders when it flips.
     */
    val translationRequested = MutableStateFlow(false)
}
