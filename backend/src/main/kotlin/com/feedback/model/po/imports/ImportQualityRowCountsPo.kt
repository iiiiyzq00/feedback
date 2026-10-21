package com.feedback.model.po.imports

data class ImportQualityRowCountsPo(
    val rowsSeen: Int, val completed: Int, val importedSuccess: Int, val reviewed: Int,
    val invalidRows: Int, val duplicateRows: Int, val staleRows: Int,
    val requiredErrors: Int, val timeErrors: Int, val enumErrors: Int
)
