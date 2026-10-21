package com.feedback.service.imports

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import org.xml.sax.InputSource

@Service
class ImportFileParser {
    fun parse(filename: String, bytes: ByteArray): ParsedImportFile {
        ensureImport(bytes.isNotEmpty()) { "导入文件不能为空" }
        return when {
            filename.endsWith(".csv", ignoreCase = true) -> parseCsv(bytes)
            filename.endsWith(".xlsx", ignoreCase = true) -> parseXlsx(bytes)
            filename.endsWith(".xls", ignoreCase = true) -> parseWorkbook(bytes)
            else -> importInvalid("IMPORT_FORMAT_UNSUPPORTED")
        }
    }

    /** 用 SAX 解析，避免为 5 万行的文件把 POI 的整个 XSSF 对象图留在内存里。 */
    private fun parseXlsx(bytes:ByteArray):ParsedImportFile {
        ByteArrayInputStream(bytes).use { input -> OPCPackage.open(input).use { pkg ->
            val xssf=XSSFReader(pkg);val styles=xssf.stylesTable;val strings=xssf.sharedStringsTable
            val iterator=xssf.sheetsData as XSSFReader.SheetIterator
            val sheets=mutableListOf<ParsedSheet>()
            while(iterator.hasNext()){
                val rows=mutableListOf<List<String>>();val stream=iterator.next();val name=iterator.sheetName
                stream.use { sheetInput ->
                    var current=mutableListOf<String>()
                    val handler=object:XSSFSheetXMLHandler.SheetContentsHandler{
                        override fun startRow(rowNum:Int){current=mutableListOf()}
                        override fun endRow(rowNum:Int){if(current.any(String::isNotBlank))rows+=current.toList()}
                        override fun cell(cellReference:String?,formattedValue:String?,comment:org.apache.poi.xssf.usermodel.XSSFComment?){
                            val column=cellReference?.let{CellReference(it).col.toInt()}?:current.size
                            while(current.size<column)current+=""
                            val value=formattedValue?.trim().orEmpty()
                            if(current.size==column)current+=value else current[column]=value
                        }
                    }
                    val xml=XMLHelper.newXMLReader()
                    xml.contentHandler=XSSFSheetXMLHandler(styles,null,strings,handler,DataFormatter(),false)
                    xml.parse(InputSource(sheetInput))
                }
                if(rows.isNotEmpty())sheets+=ParsedSheet(name,rows)
            }
            return ParsedImportFile(sheets)
        }}
    }

    private fun parseCsv(bytes: ByteArray): ParsedImportFile {
        val rows = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { parseCsvLine(it) }
            .toList()
        return ParsedImportFile(listOf(ParsedSheet("Sheet1", rows)))
    }

    private fun parseWorkbook(bytes: ByteArray): ParsedImportFile {
        ByteArrayInputStream(bytes).use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheets = (0 until workbook.numberOfSheets).map { index ->
                    val sheet = workbook.getSheetAt(index)
                    val rows = sheet.map { row ->
                        (0 until row.lastCellNum.coerceAtLeast(0)).map { cellIndex ->
                            row.getCell(cellIndex)?.toString()?.trim().orEmpty()
                        }
                    }.filter { row -> row.any { it.isNotBlank() } }
                    ParsedSheet(sheet.sheetName, rows)
                }.filter { it.rows.isNotEmpty() }
                return ParsedImportFile(sheets)
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
            index++
        }
        cells += current.toString().trim()
        return cells
    }
}

data class ParsedImportFile(val sheets: List<ParsedSheet>) {
    val totalRows: Int get() = sheets.sumOf { it.dataRows.size }
}

data class ParsedSheet(
    val name: String,
    val rows: List<List<String>>
) {
    val header: List<String> get() = rows.firstOrNull().orEmpty()
    val dataRows: List<List<String>> get() = rows.drop(1)
}
