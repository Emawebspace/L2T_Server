/*
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package l2server.gameserver.taskmanager.tasks;

import l2server.DatabasePool;
import l2server.gameserver.taskmanager.Task;
import l2server.gameserver.taskmanager.TaskManager;
import l2server.gameserver.taskmanager.TaskManager.ExecutedTask;
import l2server.gameserver.taskmanager.TaskTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * @author Layane
 */
public class TaskRecom extends Task {
	private static Logger log = LoggerFactory.getLogger(TaskRecom.class.getName());



	private static final String NAME = "recommendations";

	/**
	 * @see l2server.gameserver.taskmanager.Task#getName()
	 */
	@Override
	public String getName() {
		return NAME;
	}

	/**
	 * @see l2server.gameserver.taskmanager.Task#onTimeElapsed(l2server.gameserver.taskmanager.TaskManager.ExecutedTask)
	 */
	@Override
	public void onTimeElapsed(ExecutedTask task) {
		Connection con = null;
		try {
			con = DatabasePool.getInstance().getConnection();
			PreparedStatement statement =
					con.prepareStatement("UPDATE character_reco_bonus SET rec_left=?, time_left=?, rec_have=0 WHERE rec_have <=  20");
			statement.setInt(1, 0); // Rec left = 0
			statement.setInt(2, 3600000); // Timer = 1 hour
			statement.execute();

			statement = con.prepareStatement(
					"UPDATE character_reco_bonus SET rec_left=?, time_left=?, rec_have=GREATEST(rec_have-20,0) WHERE rec_have > 20");
			statement.setInt(1, 0); // Rec left = 0
			statement.setInt(2, 3600000); // Timer = 1 hour
			statement.execute();
			statement.close();
		} catch (Exception e) {
			log.error("Could not reset Recommendations System: " + e);
		} finally {
			DatabasePool.close(con);
		}
		log.debug("Recommendations System reseted");
	}

	/**
	 * @see l2server.gameserver.taskmanager.Task#initialize()
	 */
	@Override
	public void initialize() {
		super.initialize();
		TaskManager.addUniqueTask(NAME, TaskTypes.TYPE_GLOBAL_TASK, "1", "06:30:00", "");
	}
}
