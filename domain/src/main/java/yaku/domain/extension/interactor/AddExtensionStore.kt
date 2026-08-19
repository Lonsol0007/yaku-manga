package yaku.domain.extension.interactor

import dev.zacsweers.metro.Inject
import yaku.domain.extension.repository.ExtensionStoreRepository

@Inject
class AddExtensionStore(
    private val repository: ExtensionStoreRepository,
) {
    suspend operator fun invoke(indexUrl: String): Result<Unit> {
        return repository.insert(indexUrl)
    }
}
