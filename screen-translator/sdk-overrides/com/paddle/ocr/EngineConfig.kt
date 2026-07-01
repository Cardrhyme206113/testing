package com.paddle.ocr

enum class OCRExecutionProvider {
    AUTO,
    NNAPI,
    CPU,
}

data class EngineConfig(
    val numThreads: Int = 4,
    val executionProvider: OCRExecutionProvider = OCRExecutionProvider.AUTO,
    val allowFp16: Boolean = true,
)
