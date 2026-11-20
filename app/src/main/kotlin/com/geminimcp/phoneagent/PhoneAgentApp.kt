package com.geminimcp.phoneagent

import android.app.Application
import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.security.RateLimitCostManager
import com.geminimcp.phoneagent.security.SafetyGuard
import com.geminimcp.phoneagent.voice.VoiceConfirmationManager

class PhoneAgentApp : Application() {

    lateinit var configStore: AgentConfigStore
        private set

    lateinit var safetyGuard: SafetyGuard
        private set

    lateinit var rateLimitCostManager: RateLimitCostManager
        private set

    lateinit var confirmationManager: VoiceConfirmationManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configStore = AgentConfigStore(this)
        safetyGuard = SafetyGuard(configStore)
        rateLimitCostManager = RateLimitCostManager(configStore)
        confirmationManager = VoiceConfirmationManager()
    }

    companion object {
        @Volatile
        lateinit var instance: PhoneAgentApp
            private set
    }
}
