package yaku.domain.history.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.history.repository.HistoryRepository

@Inject
class GetTotalReadDuration(
    private val repository: HistoryRepository,
) {

    suspend fun await(): Long {
        return repository.getTotalReadDuration()
    }
}
