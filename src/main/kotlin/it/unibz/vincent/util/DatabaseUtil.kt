package it.unibz.vincent.util

import org.h2.Driver
import org.h2.jdbcx.JdbcConnectionPool
import org.h2.jdbcx.JdbcDataSource
import javax.sql.DataSource

/**
 *
 */
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