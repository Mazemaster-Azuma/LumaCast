package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * Backward-compatible entry point delegating to [AppTheme].
 */
@Composable
fun LumaCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    AppTheme(darkTheme = darkTheme, content = content)
}
