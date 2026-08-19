package yaku.data.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.combine
import yaku.data.track.anilist.Anilist
import yaku.data.track.bangumi.Bangumi
import yaku.data.track.hikka.Hikka
import yaku.data.track.kavita.Kavita
import yaku.data.track.kitsu.Kitsu
import yaku.data.track.komga.Komga
import yaku.data.track.mangabaka.MangaBaka
import yaku.data.track.mangaupdates.MangaUpdates
import yaku.data.track.myanimelist.MyAnimeList
import yaku.data.track.shikimori.Shikimori
import yaku.data.track.suwayomi.Suwayomi

@Inject
@SingleIn(AppScope::class)
class TrackerManager {

    companion object {
        const val ANILIST = 2L
        const val KITSU = 3L
        const val KAVITA = 8L
        const val MANGABAKA = 11L
    }

    val myAnimeList = MyAnimeList(1L)
    val aniList = Anilist(ANILIST)
    val kitsu = Kitsu(KITSU)
    val shikimori = Shikimori(4L)
    val bangumi = Bangumi(5L)
    val komga = Komga(6L)
    val mangaUpdates = MangaUpdates(7L)
    val kavita = Kavita(KAVITA)
    val suwayomi = Suwayomi(9L)
    val hikka = Hikka(10L)
    val mangaBaka = MangaBaka(MANGABAKA)

    val trackers = listOf(
        myAnimeList,
        aniList,
        kitsu,
        shikimori,
        bangumi,
        komga,
        mangaUpdates,
        kavita,
        suwayomi,
        hikka,
        mangaBaka,
    )

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = trackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = trackers.filter { it.id in ids }
}
