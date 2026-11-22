package com.example.mobilemathstudio.math

class ParseException(
    message: String,
    val index: Int,
    val length: Int = 1,
) : Exception(message)
