package yaku.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.FilterList
import yaku.domain.source.repository.SourcePagingSource
import yaku.domain.source.repository.SourceRepository

@Inject
class GetRemoteManga(
    private val repository: SourceRepository,
) {

    operator fun invoke(sourceId: Long, query: String, filterList: FilterList): SourcePagingSource {
        return when (query) {
            QUERY_POPULAR -> repository.getPopular(sourceId)
            QUERY_LATEST -> repository.getLatest(sourceId)
            else -> repository.search(sourceId, query, filterList)
        }
    }

    companion object {
        const val QUERY_POPULAR = "yaku.domain.source.interactor.POPULAR"
        const val QUERY_LATEST = "yaku.domain.source.interactor.LATEST"
    }
}
