package com.lifeos.ai.di

import com.lifeos.ai.data.remote.api.AiService

/**
 * Release builds never call AI providers directly: they contain no keys and no direct client,
 * and always go through the secure Firebase backend.
 */
internal fun devAiService(): AiService? = null
