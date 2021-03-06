package l2server.gameserver.instancemanager;

import l2server.Config;
import l2server.DatabasePool;
import l2server.gameserver.ThreadPoolManager;
import l2server.gameserver.datatables.AccessLevels;
import l2server.gameserver.datatables.AdminCommandAccessRights;
import l2server.gameserver.handler.AdminCommandHandler;
import l2server.gameserver.handler.IAdminCommandHandler;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.util.GMAudit;
import l2server.util.loader.annotations.Load;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * @author Pere
 */
public class OfflineAdminCommandsManager {
	private static Logger log = LoggerFactory.getLogger(OfflineAdminCommandsManager.class.getName());



	private final int TIME_BETWEEN_CHECKS = 60000; // 1 min

	private Player dummy;

	public static OfflineAdminCommandsManager getInstance() {
		return SingletonHolder.instance;
	}

	public OfflineAdminCommandsManager() {
	}
	
	@Load(dependencies = AdminCommandAccessRights.class)
	public void initialize() {
		loadDummy();
		ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(new CheckCommandsTask(), TIME_BETWEEN_CHECKS, TIME_BETWEEN_CHECKS);
	}
	
	public void loadDummy() {
		Connection con = null;

		try {
			// Retrieve the Player from the characters table of the database
			con = DatabasePool.getInstance().getConnection();

			PreparedStatement statement = con.prepareStatement("SELECT charId, char_name FROM characters WHERE char_name LIKE 'OffDummy'");
			ResultSet rset = statement.executeQuery();

			if (rset.next()) {
				Player pal = Player.load(rset.getInt("charId"));
				pal.setClient(null);
				dummy = pal;
			}
			rset.close();
			statement.close();
		} catch (Exception e) {
			log.error("Could not restore char data: " + e);
		} finally {
			DatabasePool.close(con);
		}
	}

	public void executeCommand(String author, int accessLevel, String command, int date) {
		command = "admin_" + command.substring(2);
		String commandName = command.split(" ")[0];

		IAdminCommandHandler ach = AdminCommandHandler.getInstance().getAdminCommandHandler(commandName);

		if (ach == null) {
			log.warn("No handler registered for admin command '" + commandName + "' (website)");
			saveCommandExecution(date);
			return;
		}

		if (!AdminCommandAccessRights.getInstance().hasAccess(commandName, AccessLevels.getInstance().getAccessLevel(accessLevel))) {
			log.warn("Character " + author + " tried to use admin command " + commandName + " from the website, but have no access to it!");
			saveCommandExecution(date);
			return;
		}

		if (Config.GMAUDIT) {
			GMAudit.auditGMAction(author, commandName, "no-target");
		}

		if (dummy == null) {
			loadDummy();
		}

		dummy.setName(author);
		ach.useAdminCommand(command, dummy);
		dummy.setName("OffDummy");
		saveCommandExecution(date);
	}

	private void saveCommandExecution(int date) {
		Connection con = null;

		try {
			// Retrieve the Player from the characters table of the database
			con = DatabasePool.getInstance().getConnection();

			PreparedStatement statement = con.prepareStatement("UPDATE offline_admin_commands SET executed = 1 WHERE date = ?");
			statement.setInt(1, date);
			statement.execute();
			statement.close();
		} catch (Exception e) {
			log.error("Could not set offline admin command status to executed: " + e);
		} finally {
			DatabasePool.close(con);
		}
	}

	public class CheckCommandsTask implements Runnable {
		@Override
		public void run() {
			Connection con = null;

			try {
				// Retrieve the Player from the characters table of the database
				con = DatabasePool.getInstance().getConnection();

				PreparedStatement statement = con.prepareStatement(
						"SELECT author, accessLevel, command, date FROM offline_admin_commands WHERE executed = 0 ORDER BY date ASC");
				ResultSet rset = statement.executeQuery();

				boolean someExecuted = false;
				while (rset.next()) {
					executeCommand(rset.getString("author"), rset.getInt("accessLevel"), rset.getString("command"), rset.getInt("date"));
					someExecuted = true;
				}

				rset.close();
				statement.close();

				if (someExecuted) {
					statement = con.prepareStatement("UPDATE offline_admin_commands SET executed = 1");
					statement.execute();
					statement.close();
				}
			} catch (Exception e) {
				log.error("Could not check for offline admin commands: " + e);
				e.printStackTrace();
			} finally {
				DatabasePool.close(con);
			}
		}
	}

	@SuppressWarnings("synthetic-access")
	private static class SingletonHolder {
		protected static final OfflineAdminCommandsManager instance = new OfflineAdminCommandsManager();
	}
}
