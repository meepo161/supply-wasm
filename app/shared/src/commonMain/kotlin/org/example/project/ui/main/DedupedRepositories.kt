package org.example.project.ui.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.UserRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.dto.WarehouseDto

/**
 * Объединяет одновременные одинаковые чтения (аудит N-15): при старте приложения
 * несколько экранов запрашивают одни и те же справочники. Пока запрос выполняется,
 * остальные вызовы ждут его результат. Готовый результат не кешируется, поэтому
 * следующее чтение после изменения данных всегда идёт на сервер.
 */
internal class InFlightRequest<T>(private val load: suspend () -> T) {
    private var current: CompletableDeferred<T>? = null

    suspend fun get(): T {
        while (true) {
            val running = current
            if (running == null) {
                val own = CompletableDeferred<T>()
                current = own
                try {
                    val value = load()
                    own.complete(value)
                    return value
                } catch (e: Throwable) {
                    own.completeExceptionally(e)
                    throw e
                } finally {
                    current = null
                }
            }
            try {
                return running.await()
            } catch (e: CancellationException) {
                // Отменили владельца запроса, а не нас: повторяем чтение сами.
                if (!currentCoroutineContext().isActive) throw e
            }
        }
    }
}

internal class DedupedUserRepository(private val delegate: UserRepository) : UserRepository by delegate {
    private val users = InFlightRequest { delegate.list() }
    private val options = InFlightRequest { delegate.listOptions() }
    override suspend fun list(): ApiResult<List<UserDto>> = users.get()
    override suspend fun listOptions(): ApiResult<List<UserOptionDto>> = options.get()
}

internal class DedupedProjectRepository(private val delegate: ProjectRepository) : ProjectRepository by delegate {
    private val projects = InFlightRequest { delegate.list() }
    override suspend fun list(): ApiResult<List<ProjectDto>> = projects.get()
}

internal class DedupedWmsRepository(private val delegate: WmsRepository) : WmsRepository by delegate {
    private val nomenclature = InFlightRequest { delegate.listNomenclature() }
    private val warehouses = InFlightRequest { delegate.listWarehouses() }
    override suspend fun listNomenclature(): ApiResult<List<NomenclatureDto>> = nomenclature.get()
    override suspend fun listWarehouses(): ApiResult<List<WarehouseDto>> = warehouses.get()
}
