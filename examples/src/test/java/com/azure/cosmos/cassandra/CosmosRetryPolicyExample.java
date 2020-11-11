// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.cosmos.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.session.Session;
import org.testng.annotations.Test;

import static java.lang.String.format;
import static org.testng.AssertJUnit.fail;

/**
 * This test illustrates use of the {@link CosmosRetryPolicy} class.
 * <p>
 * Preconditions:
 * <ul>
 * <li>An Apache Cassandra cluster is running and accessible through the configuration specified by application.conf.
 * </ul>
 * <p>
 * Side effects:
 * <ol>
 * <li>Creates a new keyspace {@code downgrading} in the cluster, with replication factor 3. If a
 * keyspace with this name already exists, it will be reused;
 * <li>Creates a new table {@code downgrading.sensor_data}. If a table with that name exists
 * already, it will be reused;
 * <li>Inserts a few rows, downgrading the consistency level if the operation fails;
 * <li>Queries the table, downgrading the consistency level if the operation fails;
 * <li>Displays the results on the console.
 * </ol>
 * <p>
 * Notes:
 * <ul>
 * <li>The downgrading logic here is similar to what {@code DowngradingConsistencyRetryPolicy}
 * does; feel free to adapt it to your application needs;
 * <li>You should never attempt to retry a non-idempotent write. See the driver's manual page on
 * idempotence for more information.
 * </ul>
 *
 * @see <a href="http://datastax.github.io/java-driver/manual/">Java driver online manual</a>
 */
public class CosmosRetryPolicyExample implements AutoCloseable {

    // region Fields

    private static final ConsistencyLevel CONSISTENCY_LEVEL = ConsistencyLevel.QUORUM;
    private static final int TIMEOUT = 30_000_000;

    private CqlSession session;

    // endregion

    // region Methods

    @Test(groups = { "examples" }, timeOut = TIMEOUT)
    public void canIntegrateWithCosmos() {

        try (Session ignored = this.connect()) {

            try {
                this.createSchema();
            } catch (Exception error) {
                fail(format("createSchema failed: %s", error));
            }
            try {
                this.write(CONSISTENCY_LEVEL);
            } catch (Exception error) {
                fail(format("write failed: %s", error));
            }
            try {
                ResultSet rows = this.read(CONSISTENCY_LEVEL);
                this.display(rows);
            } catch (Exception error) {
                fail(format("read failed: %s", error));
            }

        } catch (Exception error) {
            fail(format("connect failed with %s: %s", error.getClass().getCanonicalName(), error));
        }
    }

    /**
     * Closes the session and the cluster.
     */
    @Override
    public void close() {
        if (this.session != null) {
            this.session.close();
        }
    }

    // region Privates

    /**
     * Initiates a connection to the cluster specified by application.conf.
     */
    private Session connect() {
        this.session = CqlSession.builder().build();
        System.out.println("Connected to session: " + this.session.getName());
        return this.session;
    }

    /**
     * Creates the schema (keyspace) and table to verify that we can integrate with Cosmos.
     */
    private void createSchema() {

        this.session.execute(SimpleStatement.newInstance(
            "CREATE KEYSPACE IF NOT EXISTS downgrading WITH replication = {"
                + "'class':'SimpleStrategy',"
                + "'replication_factor':3"
                + "}"));

        this.session.execute(SimpleStatement.newInstance(
            "CREATE TABLE IF NOT EXISTS downgrading.sensor_data ("
                + "sensor_id uuid,"
                + "date date,"
                + "timestamp timestamp,"
                + "value double,"
                + "PRIMARY KEY ((sensor_id,date),timestamp)"
                + ")"));
    }

    /**
     * Displays the results on the console.
     *
     * @param rows the results to display.
     */
    private void display(ResultSet rows) {

        final int width1 = 38;
        final int width2 = 12;
        final int width3 = 30;
        final int width4 = 21;

        final String format = "%-" + width1 + "s" + "%-" + width2 + "s" + "%-" + width3 + "s" + "%-" + width4 + "s%n";
        System.out.printf(format, "sensor_id", "date", "timestamp", "value");
        drawLine(width1, width2, width3, width4);

        for (Row row : rows) {
            System.out.printf(format,
                row.getUuid("sensor_id"),
                row.getLocalDate("date"),
                row.getInstant("timestamp"),
                row.getDouble("value"));
        }
    }

    /**
     * Draws a line to isolate headings from rows.
     *
     * @param widths the column widths.
     */
    private static void drawLine(int... widths) {
        for (int width : widths) {
            for (int i = 1; i < width; i++) {
                System.out.print('-');
            }
            System.out.print('+');
        }
        System.out.println();
    }

    /**
     * Queries data, retrying if necessary with a downgraded CL.
     *
     * @param consistencyLevel the consistency level to apply.
     */
    private ResultSet read(ConsistencyLevel consistencyLevel) {

        System.out.printf("Reading at %s%n", consistencyLevel);

        Statement statement = SimpleStatement.newInstance(
            "SELECT sensor_id, date, timestamp, value "
                + "FROM downgrading.sensor_data "
                + "WHERE "
                + "sensor_id = 756716f7-2e54-4715-9f00-91dcbea6cf50 AND "
                + "date = '2018-02-26' AND "
                + "timestamp > '2018-02-26+01:00'")
            .setConsistencyLevel(consistencyLevel);

        ResultSet rows = this.session.execute(statement);
        System.out.println("Read succeeded at " + consistencyLevel);

        return rows;
    }

    /**
     * Inserts data, retrying if necessary with a downgraded CL.
     *
     * @param consistencyLevel the consistency level to apply.
     */
    private void write(ConsistencyLevel consistencyLevel) {

        System.out.printf("Writing at %s%n", consistencyLevel);

        BatchStatement batch = BatchStatement.newInstance(BatchType.UNLOGGED).setConsistencyLevel(consistencyLevel)
            .add(SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:53:46.345+01:00',"
                    + "2.34)"))
            .add(SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:54:27.488+01:00',"
                    + "2.47)"))
            .add(SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:56:33.739+01:00',"
                    + "2.52)"));

        this.session.execute(batch);
        System.out.println("Write succeeded at " + consistencyLevel);
    }

    // endregion
}
