package com.example.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/**
 * Centralized application typeface definitions backed strictly by locally bundled
 * font resources in `res/font/`. No runtime font downloading is used.
 */
object AppFontFamily {
    val Inter: FontFamily = FontFamily(
        Font(resId = R.font.inter, weight = FontWeight.Normal),
        Font(resId = R.font.inter, weight = FontWeight.Medium),
        Font(resId = R.font.inter, weight = FontWeight.SemiBold),
        Font(resId = R.font.inter, weight = FontWeight.Bold)
    )

    val Primary: FontFamily = Inter
}
