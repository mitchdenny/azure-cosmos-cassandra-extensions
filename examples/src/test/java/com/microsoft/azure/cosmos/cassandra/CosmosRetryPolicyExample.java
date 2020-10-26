/*
 * The MIT License (MIT)
 *
 * Copyright (c) Microsoft. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.azure.cosmos.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.retry.RetryDecision;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.servererrors.OverloadedException;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.session.Session;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.DefaultEndPoint;
import com.datastax.oss.driver.internal.core.metadata.DefaultNode;
import jdk.internal.jline.internal.Nullable;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.testng.AssertJUnit.fail;

/**
 * This test illustrates use of the {@link CosmosRetryPolicy} class.
 *
 * <p>Preconditions:
 *
 * <ul>
 * <li>An Apache Cassandra cluster is running and accessible through the contacts points
 * identified by {@link #CONTACT_POINTS} and {@link #PORT}.
 * </ul>
 * <p>
 * Side effects:
 *
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
 *
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
    private static final String[] CONTACT_POINTS;
    private static final int FIXED_BACK_OFF_TIME = 5000;
    private static final int GROWING_BACK_OFF_TIME = 1000;
    private static final int MAX_RETRY_COUNT = 5;
    private static final int PORT;
    private static final int TIMEOUT = 30000;

    static {

        String value = System.getProperty("azure.cosmos.hostname");

        if (value == null) {
            value = System.getenv("AZURE_COSMOS_HOSTNAME");
        }

        if (value == null) {
            value = "localhost";
        }

        CONTACT_POINTS = new String[] { value };
    }

    static {

        String value = System.getProperty("azure.cosmos.port");

        if (value == null) {
            value = System.getenv("AZURE_COSMOS_PORT");
        }

        if (value == null) {
            value = "10350";
        }

        PORT = Short.parseShort(value);
    }

    private CqlSession session;

    // endregion

    // region Methods

    @Test(groups = { "examples" }, timeOut = TIMEOUT)
    public void canIntegrateWithCosmos() {

        final CosmosRetryPolicy retryPolicy = new CosmosRetryPolicy(
            MAX_RETRY_COUNT,
            FIXED_BACK_OFF_TIME,
            GROWING_BACK_OFF_TIME);

        try (Session session = this.connect(CONTACT_POINTS, PORT, retryPolicy)) {

            try {
                this.createSchema();
            } catch (Exception error) {
                fail(String.format("createSchema failed: %s", error));
            }
            try {
                this.write(CONSISTENCY_LEVEL);

            } catch (Exception error) {
                fail(String.format("write failed: %s", error));
            }
            try {
                ResultSet rows = this.read(CONSISTENCY_LEVEL);
                this.display(rows);

            } catch (Exception error) {
                fail(String.format("read failed: %s", error));
            }

        } catch (Exception error) {
            fail(String.format("connect failed with %s: %s", error.getClass().getCanonicalName(), error));
        }
    }

    /**
     * Closes the session and the cluster.
     */
    @Override
    public void close() {
        if (session != null) {
            session.close();
        }
    }

    /**
     * Initiates a connection to the cluster specified by the given contact points and port.
     *
     * @param hostnames the contact points to use.
     * @param port      the port to use.
     */
    private Session connect(String[] hostnames, int port, CosmosRetryPolicy retryPolicy) {

        final Collection<EndPoint> endpoints = new ArrayList<>(hostnames.length);

        for (String hostname : hostnames) {
            final InetSocketAddress address = new InetSocketAddress(hostname, port);
        }

        this.session = CqlSession.builder().addContactEndPoints(endpoints).build();
        System.out.println("Connected to session: " + session.getName());

        return session;
    }

    /**
     * Creates the schema (keyspace) and table to verify that we can integrate with Cosmos.
     */
    private void createSchema() {

        session.execute(SimpleStatement.newInstance(
            "CREATE KEYSPACE IF NOT EXISTS downgrading WITH replication = {"
                + "'class':'SimpleStrategy',"
                + "'replication_factor':3"
                + "}"),
            GenericType.optionalOf(Void.class));

        session.execute(SimpleStatement.newInstance(
            "CREATE TABLE IF NOT EXISTS downgrading.sensor_data ("
                + "sensor_id uuid,"
                + "date date,"
                + "timestamp timestamp,"
                + "value double,"
                + "PRIMARY KEY ((sensor_id,date),timestamp)"
                + ")"),
            GenericType.optionalOf(Void.class));
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
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

        // headings
        System.out.printf(format, "sensor_id", "date", "timestamp", "value");

        // separators
        drawLine(width1, width2, width3, width4);

        // data
        for (Row row : rows) {
            System.out.printf(format,
                row.getUuid("sensor_id"),
                row.getLocalDate("date"),
                sdf.format(row.getInstant("timestamp")),
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

        Request statement =
            SimpleStatement.newInstance(
                "SELECT sensor_id, date, timestamp, value "
                    + "FROM downgrading.sensor_data "
                    + "WHERE "
                    + "sensor_id = 756716f7-2e54-4715-9f00-91dcbea6cf50 AND "
                    + "date = '2018-02-26' AND "
                    + "timestamp > '2018-02-26+01:00'")
                .setConsistencyLevel(consistencyLevel);

        ResultSet rows = session.execute(statement, GenericType.of(ResultSet.class));
        System.out.println("Read succeeded at " + consistencyLevel);

        return rows;
    }

    /**
     * Tests a retry operation
     */
    private void retry(
        CosmosRetryPolicy retryPolicy,
        int retryNumberBegin,
        int retryNumberEnd,
        RetryDecision expectedRetryDecisionType) {

        final CoordinatorException coordinatorException = new OverloadedException(new DefaultNode(
            new DefaultEndPoint(new InetSocketAddress(CONTACT_POINTS[0], PORT)),
            (InternalDriverContext) this.session.getContext()));

        final Request statement = SimpleStatement.newInstance("SELECT * FROM retry");
        final ConsistencyLevel consistencyLevel = CONSISTENCY_LEVEL;

        for (int retryNumber = retryNumberBegin; retryNumber < retryNumberEnd; retryNumber++) {

            long expectedDuration = 1_000_000 * (retryPolicy.getMaxRetryCount() == -1
                ? FIXED_BACK_OFF_TIME
                : (long) retryNumber * GROWING_BACK_OFF_TIME);

            long startTime = System.nanoTime();

            RetryDecision retryDecision = retryPolicy.onErrorResponse(statement, coordinatorException, retryNumber);

            long duration = System.nanoTime() - startTime;

            assertThat(retryDecision).isEqualTo(expectedRetryDecisionType);
            assertThat(duration).isGreaterThan(expectedDuration);
        }
    }

    /**
     * Inserts data, retrying if necessary with a downgraded CL.
     *
     * @param consistencyLevel the consistency level to apply.
     */
    private void write(@Nullable ConsistencyLevel consistencyLevel) {

        System.out.printf("Writing at %s%n", consistencyLevel);

        BatchStatement batch = BatchStatement.newInstance(BatchType.UNLOGGED).setConsistencyLevel(consistencyLevel);

        batch.add(
            SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:53:46.345+01:00',"
                    + "2.34)"));

        batch.add(
            SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:54:27.488+01:00',"
                    + "2.47)"));

        batch.add(
            SimpleStatement.newInstance(
                "INSERT INTO downgrading.sensor_data "
                    + "(sensor_id, date, timestamp, value) "
                    + "VALUES ("
                    + "756716f7-2e54-4715-9f00-91dcbea6cf50,"
                    + "'2018-02-26',"
                    + "'2018-02-26T13:56:33.739+01:00',"
                    + "2.52)"));

        session.execute(batch);

        System.out.println("Write succeeded at " + consistencyLevel);
    }
}
