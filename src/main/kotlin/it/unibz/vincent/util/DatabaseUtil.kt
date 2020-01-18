package it.unibz.vincent.util

import org.h2.Driver
import org.h2.jdbcx.JdbcConnectionPool
import org.h2.jdbcx.JdbcDataSource
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Create a new Query runner upon given url.
 * Returned [DataSource] MUST be disposed when no longer used with [.closeDatabase].
 *
 *
 * See https://h2database.com/html/features.html#database_url for H2.
 *
 * @param url of database engine (jdbc:...)
 * @return created pooled DataSource
 */
fun createDatabase(url: String): DataSource {
	return when {
		url.startsWith("jdbc:h2:") -> {
			// http://h2database.com/html/features.html#database_url
			Driver.load()
			val ds = JdbcDataSource()
			ds.setURL(url)
			JdbcConnectionPool.create(ds)
		}
		else -> throw IllegalArgumentException("Unrecognized database url: '$url'")
	}
}

/** Disposes [DataSource] objects created by [.createDatabase]  */
fun closeDatabase(ds: DataSource) {
	// This is admittedly kinda ugly, but required to support other database engines,
	// since the [dispose()] method is not on the DataSource interface.
	if (ds is JdbcConnectionPool) {
		ds.dispose()
	} else {
		throw IllegalArgumentException("Unrecognized data source: $ds")
	}
}

enum class SQLErrorType(val state:String) {
	CONSTRAINT_VIOLATION("23000"),
	/** [org.h2.api.ErrorCode.NULL_NOT_ALLOWED] */
	NULL_NOT_ALLOWED("23502"),
	/** [org.h2.api.ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS_1] */
	FOREIGN_CHILD_EXISTS("23503"),
	/** [org.h2.api.ErrorCode.DUPLICATE_KEY_1] */
	DUPLICATE_KEY("23505"),
	/** [org.h2.api.ErrorCode.REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING_1] */
	FOREIGN_PARENT_MISSING("23506"),
	/** [org.h2.api.ErrorCode.NO_DEFAULT_SET_1] */
	NO_DEFAULT_SET("23507"),
	/** [org.h2.api.ErrorCode.CHECK_CONSTRAINT_VIOLATED_1] */
	CHECK_CONSTRAINT_VIOLATED("23513"),
	/** [org.h2.api.ErrorCode.CHECK_CONSTRAINT_INVALID] */
	CHECK_CONSTRAINT_INVALID("23514")
}

private val SQL_ERROR_TYPES = SQLErrorType.values()

fun SQLException.type():SQLErrorType? {
	val state = sqlState?.takeIf { it.length == 5 } ?: return null
	return SQL_ERROR_TYPES.find { it.state == state }
}