package yaku.domain.source.models

import yaku.domain.chapter.model.Chapter
import yaku.domain.manga.model.Manga

data class RemoteMangaUpdate(
    val manga: Manga,
    val newChapters: List<Chapter>,
)
