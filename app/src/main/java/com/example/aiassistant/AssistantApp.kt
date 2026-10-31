package com.example.aiassistant

import android.app.Application
import android.content.Context
import com.example.aiassistant.data.SecureKeyStore
import com.example.aiassistant.data.SettingsRepository
import com.example.aiassistant.memory.LocalConversationRepository
import com.example.aiassistant.permissions.PermissionManager
import com.example.aiassistant.voice.AssistantRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AssistantApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settingsRepository: SettingsRepository; private set
    lateinit var keyStore: SecureKeyStore; private set
    lateinit var conversations: LocalConversationRepository; private set
    lateinit var permissions: PermissionManager; private set
    lateinit var runtime: AssistantRuntime; private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this, appScope)
        keyStore = SecureKeyStore(this)
        conversations = LocalConversationRepository(this)
        permissions = PermissionManager(this)
        runtime = AssistantRuntime(this, appScope, settingsRepository, keyStore, conversations, permissions)
    }
}

val Context.assistantApp: AssistantApp get() = applicationContext as AssistantApp
