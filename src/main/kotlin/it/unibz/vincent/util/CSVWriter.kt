package it.unibz.vincent.util

import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.Writer

/**
 *
 */
class CSVWriter(private val out: Writer, private val separator:String = ",") : Closeable {

	private var firstRowItems = -1
	private var rowItems = 0
	private var row = 1

	fun item(value:String?) {
		if (rowItems > 0) {
			out.write(separator)
		}
		rowItems++

		if (value == null) {
			return
		} else if ('"' in value || '\n' in value || '\r' in value || separator in value) {
			out.write('"'.toInt())
			for (c in value) {
				out.append(c)
				if (c == '"') {
					out.append('"')
				}
			}
			out.write('"'.toInt())
		} else {
			out.write(value)
		}
	}

	fun row() {
		if (firstRowItems == -1) {
			firstRowItems = rowItems
		} else if (firstRowItems != rowItems) {
			LOG.warn("Row {} has {} items, while the header specifies {} items", row, rowItems, firstRowItems)
		}
		rowItems = 0
		row++

		out.write("\r\n")
	}

	override fun close() {
		out.close()
	}

	private companion object {
		private val LOG = LoggerFactory.getLogger(CSVWriter::class.java)
	}
}