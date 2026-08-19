package yaku.domain.history.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.history.model.HistoryUpdate
import yaku.domain.history.repository.HistoryRepository

@Inject
class UpsertHistory(
    private val historyRepository: HistoryRepository,
) {

    suspend fun await(historyUpdate: HistoryUpdate) {
        historyRepository.upsertHistory(historyUpdate)
    }
}
