package it.unibz.vincent.util

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.H2Dialect

/**
 *
 */
class MergeStatement(val table: Table, val keys:List<Column<*>>) : UpdateBuilder<Int>(StatementType.OTHER, listOf(table)) {

	override fun PreparedStatementApi.executeInternal(transaction: Transaction): Int {
		if (values.isEmpty()) return 0
		return executeUpdate()
	}

	override fun prepareSQL(transaction: Transaction): String {
		assert(values.isNotEmpty())
		assert(transaction.db.dialect is H2Dialect)
		// MERGE INTO <tableName> [ (columnName, ...) ] [KEY (columnName, ...) ] <insertValues|query>
		return with(QueryBuilder(true)) {
			+"MERGE INTO "
			+table.tableName
			+" ("
			var first = true
			for ((key) in values) {
				if (first) {
					first = false
				} else {
					+", "
				}
				+transaction.identity(key)
			}
			+")"

			if (keys.isNotEmpty()) {
				+" KEY ("
				first = true
				for (value in keys) {
					if (first) {
						first = false
					} else {
						+", "
					}
					+transaction.identity(value)
				}
				+")"
			}

			+" VALUES ("
			first = true
			for (value in values) {
				if (first) {
					first = false
				} else {
					+", "
				}
				registerArgument(value.key, value.value)
			}
			+")"

			toString()
		}
	}

	override fun arguments(): Iterable<Iterable<Pair<IColumnType, Any?>>> = QueryBuilder(true).run {
		values.forEach {
			registerArgument(it.key, it.value)
		}
		if (args.isNotEmpty()) listOf(args) else emptyList()
	}
}

fun <T:Table> T.merge(keys:List<Column<*>> = emptyList(), body: T.(MergeStatement)->Unit): Int {
	val query = MergeStatement(this, keys)
	body(query)
	return query.execute(TransactionManager.current())!!
}

open class VarCharIgnoreCaseColumnType(val colLength: Int?) : StringColumnType()  {
	override fun sqlType(): String = buildString {
		append("VARCHAR_IGNORECASE")
		if (colLength != null) {
			append('(').append(colLength).append(')')
		}
	}
}

fun Table.varcharIgnoreCase(name: String, length: Int? = null): Column<String> = registerColumn(name, VarCharIgnoreCaseColumnType(length))