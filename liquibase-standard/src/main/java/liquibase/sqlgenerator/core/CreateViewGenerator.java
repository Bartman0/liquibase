package liquibase.sqlgenerator.core;

import liquibase.CatalogAndSchema;
import liquibase.GlobalConfiguration;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.core.*;
import liquibase.exception.DatabaseException;
import liquibase.exception.ValidationErrors;
import liquibase.parser.LiquibaseSqlParser;
import liquibase.parser.SqlParserFactory;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.statement.core.CreateViewStatement;
import liquibase.structure.core.Relation;
import liquibase.structure.core.View;
import liquibase.util.StringClauses;

import java.util.ArrayList;
import java.util.List;

public class CreateViewGenerator extends AbstractSqlGenerator<CreateViewStatement> {

    @Override
    public ValidationErrors validate(CreateViewStatement createViewStatement, Database database,
                                     SqlGeneratorChain sqlGeneratorChain) {

        if (database instanceof InformixDatabase) {
            return new CreateViewGeneratorInformix().validate(createViewStatement, database, sqlGeneratorChain);
        }

        ValidationErrors validationErrors = new ValidationErrors();

        validationErrors.checkRequiredField("viewName", createViewStatement.getViewName());

        if (createViewStatement.isReplaceIfExists()) {
            //
            // Special validation for DB2
            // replaceIfExists is not allowed for version < 10.5
            //
            if (! db2VersionSupportsCreateOrReplace(database)) {
                validationErrors.addError("'replaceIfExists' is not allowed on DB2 version < 10.5");
            }
            else {
                validationErrors.checkDisallowedField("replaceIfExists", createViewStatement.isReplaceIfExists(), database, Db2zDatabase.class, DerbyDatabase.class, InformixDatabase.class);
            }
        }

        return validationErrors;
    }

    @Override
    public Sql[] generateSql(CreateViewStatement statement, Database database, SqlGeneratorChain sqlGeneratorChain) {

        if (database instanceof InformixDatabase) {
            return new CreateViewGeneratorInformix().generateSql(statement, database, sqlGeneratorChain);
        }

        List<Sql> sql = new ArrayList<>();

        if (database instanceof SybaseASADatabase) {
            // Switching off view definition checks to allow creation of dependent views even if dependency does not yet exist.
            sql.add(new UnparsedSql("SET TEMPORARY OPTION force_view_creation='ON'"));
        }

        SqlParserFactory sqlParserFactory = Scope.getCurrentScope().getSingleton(SqlParserFactory.class);
        LiquibaseSqlParser sqlParser = sqlParserFactory.getSqlParser();
        StringClauses viewDefinition = sqlParser.parse(statement.getSelectQuery(), true, true);

        if (!statement.isFullDefinition()) {
            viewDefinition
                    .prepend(" ")
                    .prepend("AS")
                    .prepend(" ")
                .prepend(database.escapeViewName(
                    statement.getCatalogName(), statement.getSchemaName(), statement.getViewName()))
                    .prepend(" ")
                    .prepend("VIEW")
                    .prepend(" ")
                    .prepend("CREATE");
        }

        if (statement.isReplaceIfExists()) {
            if (database instanceof FirebirdDatabase) {
                viewDefinition.replace("CREATE", "RECREATE");
            } else if (database instanceof MSSQLDatabase) {
                //
                // If we find CREATE OR ALTER then we will let the SQL itself take
                // care of the existence of the view
                //
                if (!viewDefinitionContains(viewDefinition,  "CREATE OR ALTER")) {
                    //from http://stackoverflow.com/questions/163246/sql-server-equivalent-to-oracles-create-or-replace-view
                    CatalogAndSchema schema = new CatalogAndSchema(
                            statement.getCatalogName(), statement.getSchemaName()).customize(database);
                    sql.add(new UnparsedSql(
                            "IF NOT EXISTS (SELECT * FROM sys.views WHERE object_id = OBJECT_ID(N'["
                                    + schema.getSchemaName()
                                    + "].[" + statement.getViewName()
                                    + "]'))\n"
                                    + "    EXEC sp_executesql N'CREATE VIEW [" + schema.getSchemaName() + "].["
                                    + statement.getViewName()
                                    + "] AS SELECT " +
                                    "''This is a code stub which will be replaced by an Alter Statement'' as [code_stub]'"));
                    viewDefinition.replace("CREATE", "ALTER");
                }
            } else if (shouldPrependDropViewStatement(database)) {
                sql.add(new UnparsedSql(
                    "DROP VIEW IF EXISTS " + database.escapeViewName(statement.getCatalogName(),
                    statement.getSchemaName(), statement.getViewName())));
            } else {
                //
                // Do not generate CREATE OR REPLACE if:
                // 1) It is already in the SQL
                // 2) The DB2 version is < 10.5
                //
                if (!statement.getSelectQuery().toUpperCase().contains("OR REPLACE")) {
                    viewDefinition.replace("CREATE", "CREATE OR REPLACE");
                }
            }
        }
        sql.add(new UnparsedSql(viewDefinition.toString(), getAffectedView(statement)));
        return sql.toArray(EMPTY_SQL);
    }

    //
    // Check to see if the collection of clauses are in the view definition
    //
    private boolean viewDefinitionContains(StringClauses viewDefinition, String phrase) {
        return viewDefinition.toString()
                             .toUpperCase()
                             .replace('\n',' ')
                             .replaceAll("\\s{2,}"," ")
                             .contains(phrase.toUpperCase());
    }

    private boolean shouldPrependDropViewStatement(Database database) {
        // allow overriding the value of the dropIfCannotReplace attribute
        // from liquibase.properties or command line
        boolean dropIfCannotReplace = false;
        if (GlobalConfiguration.ALWAYS_DROP_INSTEAD_OF_REPLACE.getCurrentValue() != null) {
            dropIfCannotReplace = GlobalConfiguration.ALWAYS_DROP_INSTEAD_OF_REPLACE.getCurrentValue();
        } 
        return database instanceof HsqlDatabase ||
              (database instanceof PostgresDatabase && dropIfCannotReplace);
    }

    //
    // Return true if non-DB2 database or DB2 version >= 10.5
    //
    private boolean db2VersionSupportsCreateOrReplace(Database database) {
        //
        // Return true for all non-DB2 databases
        //
        if (! (database instanceof DB2Database)) {
            return true;
        }

        //
        // ASSERT:  We have a DB2 database
        // DB2 must be version >= 10.5
        //
        int majorVersion = getMajorVersion(database);
        if (majorVersion < 10) {
            return false;
        }

        //
        // ASSERT:  We have a version >= 10
        // If it is > 10 then we return true
        // If it is == 10 then we check the minor version
        //
        int minorVersion = getMinorVersion(database);
        return majorVersion > 10 || minorVersion >= 5;
    }

    private int getMajorVersion(Database database) {
        int majorVersion;
        try {
            majorVersion = database.getDatabaseMajorVersion();
        }
        catch (DatabaseException dbe) {
            majorVersion = -1;
        }
        return majorVersion;
    }

    private int getMinorVersion(Database database) {
        int minorVersion;
        try {
            minorVersion = database.getDatabaseMinorVersion();
        }
        catch (DatabaseException dbe) {
            minorVersion = -1;
        }
        return minorVersion;
    }

    protected Relation getAffectedView(CreateViewStatement statement) {
        return new View().setName(statement.getViewName())
            .setSchema(statement.getCatalogName(), statement.getSchemaName());
    }
}
