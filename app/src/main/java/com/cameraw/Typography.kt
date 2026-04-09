package com.cameraw

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Techna = FontFamily(
    Font(R.font.google, FontWeight.Normal),
    Font(R.font.google_bold, FontWeight.Bold),
    Font(R.font.google_bold, FontWeight.Black)
)

val RecorderTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp
    ),
    displayMedium = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp
    ),
    displaySmall = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Techna,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)