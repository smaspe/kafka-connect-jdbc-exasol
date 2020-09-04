package com.exasol.connect.jdbc.dialect;

import io.confluent.connect.jdbc.source.JdbcSourceConnectorConfig;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Timestamp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.dialect.DatabaseDialectProvider.SubprotocolBasedProvider;
import io.confluent.connect.jdbc.dialect.DropOptions;
import io.confluent.connect.jdbc.dialect.GenericDatabaseDialect;
import io.confluent.connect.jdbc.sink.metadata.SinkRecordField;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.ExpressionBuilder;
import io.confluent.connect.jdbc.util.IdentifierRules;
import io.confluent.connect.jdbc.util.TableId;

/**
 * A {@link DatabaseDialect} for Exasol.
 */
public class ExasolDatabaseDialect extends GenericDatabaseDialect {

  /**
   * The provider for {@link ExasolDatabaseDialect}.
   */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(ExasolDatabaseDialect.class.getSimpleName(), "exa");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new ExasolDatabaseDialect(config);
    }
  }

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public ExasolDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "\"", "\""));
  }

  private static final ConcurrentMap<List<String>, Connection> CONNECTIONS = new ConcurrentHashMap<>();
  private static <T extends Exception, R> R sneakyThrow(Callable<R> t) throws T {
    try {
      return t.call();
    }catch (Exception e) {
      throw (T) t;
    }
  }

  // TODO Add counter to enable closing the connection when no longer used by any sink
  // TODO Manage pooling of connections
  @Override
  public Connection getConnection() throws SQLException {
    return CONNECTIONS.computeIfAbsent(
            Arrays.asList(
                    config.getString(JdbcSourceConnectorConfig.CONNECTION_USER_CONFIG),
                    config.getString(JdbcSourceConnectorConfig.CONNECTION_PASSWORD_CONFIG),
                    jdbcUrl),
            url -> sneakyThrow(super::getConnection));
  }

  @Override
  public void close() {
    // Don't actually close.
    // TODO handle this better: cached connection provider closes connections when deemed no longer valid,
    // so we need a mechanism to close connections anyway
    //super.close();
  }

  @Override
  protected String getSqlType(SinkRecordField field) {
    if (field.schemaName() != null) {
      switch (field.schemaName()) {
        case Decimal.LOGICAL_NAME:
          return "DECIMAL(36," + field.schemaParameters().get(Decimal.SCALE_FIELD) + ")";
        case Date.LOGICAL_NAME:
          return "DATE";
        // case Time.LOGICAL_NAME:
        // TIME is not supported (-> INT32)
        case Timestamp.LOGICAL_NAME:
          return "TIMESTAMP";
        default:
          // fall through to normal types
      }
    }
    switch (field.schemaType()) {
      case INT8:
        return "DECIMAL(3,0)";
      case INT16:
        return "DECIMAL(5,0)";
      case INT32:
        return "DECIMAL(10,0)";
      case INT64:
        return "DECIMAL(19,0)";
      case FLOAT32:
        return "FLOAT";
      case FLOAT64:
        return "DOUBLE";
      case BOOLEAN:
        return "BOOLEAN";
      case STRING:
        return "CLOB";
      // case BYTES:
      // BLOB is not supported
      default:
        return super.getSqlType(field);
    }
  }

  /**
   * Based on:
   * - https://docs.exasol.com/sql_references/data_types/datatypealiases.htm
   */
  @Override
  protected Integer getSqlTypeForSchema(Schema schema) {
    if (schema.name() != null) {
      switch (schema.name()) {
        case Date.LOGICAL_NAME:
          return Types.DATE;
        case Decimal.LOGICAL_NAME:
          return Types.DECIMAL;
        // case Time.LOGICAL_NAME:
        // TIME is not supported (-> INT32)
        case org.apache.kafka.connect.data.Timestamp.LOGICAL_NAME:
          return Types.TIMESTAMP;
        default:
          // fall through to normal types
      }
    }
    switch (schema.type()) {
      case INT8:
        return Types.TINYINT;
      case INT16:
        return Types.SMALLINT;
      case INT32:
        return Types.INTEGER;
      case INT64:
        return Types.BIGINT;
      case FLOAT32:
        return Types.FLOAT;
      case FLOAT64:
        return Types.DOUBLE;
      case BOOLEAN:
        return Types.BOOLEAN;
      case STRING:
        return Types.VARCHAR;
      // case BYTES:
      // BYTES not supported
      default:
    }
    return super.getSqlTypeForSchema(schema);
  }

  @Override
  public String buildDropTableStatement(
      TableId table,
      DropOptions options
  ) {
    ExpressionBuilder builder = expressionBuilder();

    builder.append("DROP TABLE");
    if (options.ifExists()) {
      builder.append(" IF EXISTS");
    }
    builder.append(" " + table);
    if (options.cascade()) {
      builder.append(" CASCADE CONSTRAINTS");
    }
    return builder.toString();
  }

  @Override
  public List<String> buildAlterTable(
      TableId table,
      Collection<SinkRecordField> fields
  ) {
    final List<String> queries = new ArrayList<>(fields.size());
    for (SinkRecordField field : fields) {
      queries.addAll(super.buildAlterTable(table, Collections.singleton(field)));
    }
    return queries;
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("MERGE INTO ");
    builder.append(table);
    builder.append(" AS target USING (SELECT ");
    builder.appendList()
           .delimitedBy(", ")
           .transformedBy(ExpressionBuilder.columnNamesWithPrefix("? AS "))
           .of(keyColumns, nonKeyColumns);
    builder.append(") AS incoming ON (");
    builder.appendList()
           .delimitedBy(" AND ")
           .transformedBy(this::transformAs)
           .of(keyColumns);
    builder.append(")");
    if (nonKeyColumns != null && !nonKeyColumns.isEmpty()) {
      builder.append(" WHEN MATCHED THEN UPDATE SET ");
      builder.appendList()
             .delimitedBy(",")
             .transformedBy(this::transformUpdate)
             .of(nonKeyColumns);
    }
    builder.append(" WHEN NOT MATCHED THEN INSERT (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(nonKeyColumns, keyColumns);
    builder.append(") VALUES (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNamesWithPrefix("incoming."))
           .of(nonKeyColumns, keyColumns);
    builder.append(")");
    return builder.toString();
  }

  private void transformAs(ExpressionBuilder builder, ColumnId col) {
    builder.append("target.")
           .appendIdentifierQuoted(col.name())
           .append("=incoming.")
           .appendIdentifierQuoted(col.name());
  }

  private void transformUpdate(ExpressionBuilder builder, ColumnId col) {
    builder.appendIdentifierQuoted(col.name())
           .append("=incoming.")
           .appendIdentifierQuoted(col.name());
  }

}
