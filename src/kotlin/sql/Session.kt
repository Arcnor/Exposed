package kotlin.sql

import org.h2.jdbc.JdbcConnection
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Pattern
import kotlin.dao.EntityCache
import kotlin.properties.Delegates

public class Key<T>()
@suppress("UNCHECKED_CAST")
open class UserDataHolder() {
    private val userdata = HashMap<Key<*>, Any?>()

    public fun <T:Any> putUserData(key: Key<T>, value: T?) {
        userdata[key] = value
    }

    public fun <T:Any> getUserData(key: Key<T>) : T? {
        return userdata[key] as T?
    }

    public fun <T:Any> getOrCreate(key: Key<T>, init: ()->T): T {
        if (userdata.containsKey(key)) {
            return userdata[key] as T
        }

        val new = init()
        userdata[key] = new
        return new
    }
}

class Session (val db: Database, val connector: ()-> Connection): UserDataHolder() {
    private var _connection: Connection? = null
    val connection: Connection get() {
        if (_connection == null) {
            _connection = connector()
        }
        return _connection!!
    }

    val identityQuoteString by Delegates.lazy { connection.getMetaData()!!.getIdentifierQuoteString()!! }
    val extraNameCharacters by Delegates.lazy {connection.getMetaData()!!.getExtraNameCharacters()!!}
    val identifierPattern = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$")
    val keywords = arrayListOf("key")
    val logger = CompositeSqlLogger()

    var statementCount: Int = 0
    var duration: Long = 0
    var warnLongQueriesDuration: Long = 2000
    var debug = false
    var selectsForUpdate = false

    val statements = StringBuilder()
    val outerSession = threadLocal.get()

    init {
        logger.addLogger(Log4jSqlLogger())
        threadLocal.set(this)
    }

    val vendor: DatabaseVendor by Delegates.blockingLazy {
        val url = connection.getMetaData()!!.getURL()!!
        when {
            url.startsWith("jdbc:mysql") -> DatabaseVendor.MySql
            url.startsWith("jdbc:oracle") -> DatabaseVendor.Oracle
            url.startsWith("jdbc:sqlserver") -> DatabaseVendor.SQLServer
            url.startsWith("jdbc:postgresql") -> DatabaseVendor.PostgreSQL
            url.startsWith("jdbc:h2") -> DatabaseVendor.H2
            else -> error("Unknown database type $url")
        }
    }

    fun vendorCompatibleWith(): DatabaseVendor {
        if (vendor == DatabaseVendor.H2) {
            return ((connection as? JdbcConnection)?.getSession() as? org.h2.engine.Session)?.getDatabase()?.getMode()?.let { mode ->
                DatabaseVendor.values().singleOrNull { it.name().equals(mode.getName(), true) }
            } ?: vendor
        }

        return vendor
    }

    fun commit() {
        flushCache()
        _connection?.let {
            it.commit()
        }
    }

    fun flushCache() {
        EntityCache.getOrCreate(this).flush()
    }

    fun rollback() {
        _connection?. let {
            if (!it.isClosed()) it.rollback()
        }
    }

    private fun describeStatement(args: List<Pair<ColumnType, Any?>>, delta: Long, stmt: String): String {
        return "[${delta}ms] ${expandArgs(stmt, args).take(1024)}\n\n"
    }

    inner class BatchContext {
        var stmt: String = ""
        var args: List<Pair<ColumnType, Any?>> = listOf()

        fun log(stmt: String, args: List<Pair<ColumnType, Any?>>) {
            this.stmt = stmt
            this.args = args
            logger.log(stmt, args)
        }
    }

    fun <T> execBatch(body: BatchContext.() -> T): T {
        val context = BatchContext()
        statementCount++

        val start = System.currentTimeMillis()
        val answer = context.body()
        val delta = System.currentTimeMillis() - start

        duration += delta

        if (debug) {
            statements.append(describeStatement(context.args, delta, context.stmt))
        }

        if (delta > warnLongQueriesDuration) {
            exposedLogger.warn("Long query: ${describeStatement(context.args, delta, context.stmt)}", RuntimeException())
        }

        return answer
    }

    fun <T> exec(stmt: String, args: List<Pair<ColumnType, Any?>> = listOf(), body: () -> T): T {
        return execBatch {
            log(stmt, args)
            body()
        }
    }

    fun createStatements (vararg tables: Table) : List<String> {
        val statements = ArrayList<String>()
        if (tables.isEmpty())
            return statements

        val newTables = ArrayList<Table>()
        val tablesInDatabase = allTablesNames()

        for (table in tables) {
            val exists = tablesInDatabase.contains(table.tableName)

            if(exists) continue else newTables.add(table)

            // create table
            val ddl = table.ddl
            statements.add(ddl)

            // create indices
            for (table_index in table.indices) {
                statements.add(createIndex(table_index.first, table_index.second))
            }
        }

        for (table in newTables) {
            // foreign keys
            for (column in table.columns) {
                if (column.referee != null) {
                    statements.add(createFKey(column))
                }
            }
        }
        return statements
    }

    fun create(vararg tables: Table) {
        val statements = createStatements(*tables)
        for (statement in statements) {
            exec(statement) {
                connection.createStatement().executeUpdate(statement)
            }
        }
    }

    private fun addMissingColumnsStatements (vararg tables: Table): List<String> {
        val statements = ArrayList<String>()
        if (tables.isEmpty())
            return statements

        val existingTableColumns = tableColumns()

        for (table in tables) {
            //create columns
            val missingTableColumns = table.columns.filterNot { existingTableColumns[table.tableName]?.map { it.first }?.contains(it.name) ?: true }
            for (column in missingTableColumns) {
                statements.add(column.ddl)
            }

            // create indexes with new columns
            for (table_index in table.indices) {
                if (table_index.first.any { missingTableColumns.contains(it) }) {
                    val alterTable = createIndex(table_index.first, table_index.second)
                    statements.add(alterTable)
                }
            }

            // sync nullability of existing columns
            val incorrectNullabilityColumns = table.columns.filter { existingTableColumns[table.tableName]?.contains(it.name to !it.columnType.nullable) ?: false}
            for (column in incorrectNullabilityColumns) {
                statements.add(column.modifyStatement())
            }
        }

        val existingColumnConstraint = columnConstraints(*tables)

        for (table in tables) {
            for (column in table.columns) {
                if (column.referee != null) {
                    val existingConstraint = existingColumnConstraint.get(Pair(table.tableName, column.name))?.firstOrNull()
                    if (existingConstraint == null) {
                        statements.add(createFKey(column))
                    } else if (existingConstraint.referencedTable != column.referee!!.table.tableName
                            || (column.onDelete ?: ReferenceOption.RESTRICT) != existingConstraint.deleteRule) {
                        statements.add(existingConstraint.dropStatement())
                        statements.add(createFKey(column))
                    }
                }
            }
        }

        return statements
    }

    fun createMissingTablesAndColumns(vararg tables: Table) {
        withDataBaseLock {
            val statements = createStatements(*tables) + addMissingColumnsStatements(*tables) + checkMappingConsistence(*tables)
            for (statement in statements) {
                exec(statement) {
                    connection.createStatement().executeUpdate(statement)
                }
            }
        }
    }

    fun <T>withDataBaseLock(body: () -> T): T {
        connection.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS LockTable(fake bit unique)")
        connection.createStatement().executeUpdate("INSERT IGNORE INTO LockTable (fake) VALUE (true)")
        connection.createStatement().execute("SELECT * FROM LockTable FOR UPDATE")
        return body()
    }

    fun drop(vararg tables: Table) {
        for (table in tables) {
            val ddl = table.dropStatement()
            exec(ddl) {
                connection.createStatement().executeUpdate(ddl)
            }
        }
    }

    private fun needQuotes (identity: String) : Boolean {
        return keywords.contains (identity) || !identifierPattern.matcher(identity).matches()
    }

    private fun quoteIfNecessary (identity: String) : String {
        return (identity.split('.') map {quoteTokenIfNecessary(it)}).join(".")
    }

    private fun quoteTokenIfNecessary(token: String) : String {
        return if (needQuotes(token)) "$identityQuoteString$token$identityQuoteString" else token
    }

    fun identity(table: Table): String {
        return quoteIfNecessary(table.tableName)
    }

    fun fullIdentity(column: Column<*>): String {
        return "${quoteIfNecessary(column.table.tableName)}.${quoteIfNecessary(column.name)}"
    }

    fun identity(column: Column<*>): String {
        return quoteIfNecessary(column.name)
    }

    fun createFKey(reference: Column<*>): String = ForeignKeyConstraint.from(reference).createStatement()

    fun createIndex(columns: Array<out Column<*>>, isUnique: Boolean): String = Index.forColumns(*columns, unique = isUnique).createStatement()

    fun autoIncrement(column: Column<*>): String {
        return when (vendor) {
            DatabaseVendor.MySql,
            DatabaseVendor.SQLServer,
            DatabaseVendor.H2 -> {
                "AUTO_INCREMENT"
            }
            else -> throw UnsupportedOperationException("Unsupported driver: " + vendor)
        }
    }

    private val statementsCache = HashMap<String, PreparedStatement>()
    fun prepareStatement(sql: String, autoincs: List<String>? = null): PreparedStatement {
        return statementsCache.getOrPut(sql) {
            if (autoincs == null) {
                connection.prepareStatement(sql)!!
            }
            else {
                connection.prepareStatement(sql, autoincs.copyToArray())!!
            }
        }
    }

    fun close() {
        for (stmt in statementsCache.values()) {
            stmt.close()
        }

        threadLocal.set(outerSession)
        _connection?.close()
    }

    companion object {
        val threadLocal = ThreadLocal<Session>()

        fun hasSession(): Boolean = tryGet() != null

        fun tryGet(): Session? = threadLocal.get()

        fun get(): Session {
            return tryGet() ?: error("No session in context. Use transaction?")
        }
    }
}
