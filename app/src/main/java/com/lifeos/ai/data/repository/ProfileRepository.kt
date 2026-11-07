package com.lifeos.ai.data.repository

import android.net.Uri
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.entity.UserEntity
import com.lifeos.ai.data.local.files.PrivateFileStore
import com.lifeos.ai.domain.model.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProfileRepository @Inject constructor(
    private val db: LifeOSDatabase,
    private val session: SessionManager,
    private val files: PrivateFileStore,
) {
    val profile: Flow<UserProfile> = session.authUser.flatMapLatest { auth ->
        val uid = auth?.uid ?: LOCAL_USER_ID
        combine(db.userDao().observe(uid), session.authUser) { local, a ->
            UserProfile(
                userId = uid,
                name = local?.name?.takeIf { it.isNotBlank() } ?: a?.displayName.orEmpty(),
                email = a?.email,
                photoPath = local?.profileImage,
                isGuest = a == null,
                emailVerified = a?.emailVerified ?: false,
            )
        }
    }

    suspend fun setPhoto(uri: Uri) {
        val path = files.saveProfileImage(uri)
        val uid = session.currentUserId()
        val existing = db.userDao().get(uid)
        db.userDao().upsert(
            existing?.copy(profileImage = path)
                ?: UserEntity(uid, session.authUser.value?.displayName.orEmpty(), session.authUser.value?.email, path, System.currentTimeMillis())
        )
    }

    suspend fun setLocalName(name: String) {
        val uid = session.currentUserId()
        val existing = db.userDao().get(uid)
        db.userDao().upsert(existing?.copy(name = name) ?: UserEntity(uid, name, null, null, System.currentTimeMillis()))
    }
}
