package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import sh.haven.core.data.db.ConnectionLogDao
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionLogRepository @Inject constructor(
    private val connectionLogDao: ConnectionLogDao,
    private val preferencesRepository: UserPreferencesRepository,
) {
    suspend fun logEvent(
        profileId: String,
        status: ConnectionLog.Status,
        durationMs: Long = 0,
        details: String? = null,
    ) {
        if (!preferencesRepository.connectionLoggingEnabled.first()) return
        connectionLogDao.insert(
            ConnectionLog(profileId = profileId, status = status, durationMs = durationMs, details = details)
        )
    }

    fun observeAll(limit: Int = 200): Flow<List<ConnectionLog>> =
        connectionLogDao.observeAll(limit)

    fun observeForProfile(profileId: String, limit: Int = 50): Flow<List<ConnectionLog>> =
        connectionLogDao.observeForProfile(profileId, limit)

    suspend fun clearAll() = connectionLogDao.deleteAll()
}
