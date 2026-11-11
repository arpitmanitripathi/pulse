package dev.pulse.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Runs a job on at most one instance at a time, using a PostgreSQL advisory lock.
 *
 * <p>The lock belongs to a database session, so it is taken on a connection that is kept
 * open (and idle) until the job finishes; the job itself uses other pooled connections.
 * If the instance dies, its connection closes and PostgreSQL frees the lock.
 */
@Component
public class AdvisoryLock {

	public static final long ROLLUP = 6_001;
	public static final long RETENTION = 6_002;

	private final DataSource dataSource;

	public AdvisoryLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** @return false if another instance holds the lock (the job was skipped) */
	public boolean runExclusively(long key, Runnable job) {
		try (Connection connection = dataSource.getConnection()) {
			if (!call(connection, "SELECT pg_try_advisory_lock(?)", key)) {
				return false;
			}
			try {
				job.run();
				return true;
			}
			finally {
				call(connection, "SELECT pg_advisory_unlock(?)", key);
			}
		}
		catch (SQLException ex) {
			throw new DataAccessResourceFailureException("Advisory lock " + key + " failed", ex);
		}
	}

	private static boolean call(Connection connection, String sql, long key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, key);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getBoolean(1);
			}
		}
	}

}
