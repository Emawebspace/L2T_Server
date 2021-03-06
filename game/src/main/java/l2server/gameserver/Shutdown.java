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

package l2server.gameserver;

import l2server.Config;
import l2server.DatabasePool;
import l2server.gameserver.datatables.ClanTable;
import l2server.gameserver.datatables.OfflineTradersTable;
import l2server.gameserver.events.DamageManager;
import l2server.gameserver.events.LotterySystem;
import l2server.gameserver.instancemanager.*;
import l2server.gameserver.model.World;
import l2server.gameserver.model.actor.instance.Player;
import l2server.gameserver.model.entity.ClanWarManager;
import l2server.gameserver.model.olympiad.HeroesManager;
import l2server.gameserver.model.olympiad.Olympiad;
import l2server.gameserver.network.L2GameClient;
import l2server.gameserver.network.SystemMessageId;
import l2server.gameserver.network.serverpackets.ExShowScreenMessage;
import l2server.gameserver.network.serverpackets.ServerClose;
import l2server.gameserver.network.serverpackets.SystemMessage;
import l2server.gameserver.util.Broadcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * This class provides the functions for shutting down and restarting the server
 * It closes all open clientconnections and saves all data.
 *
 * @version $Revision: 1.2.4.5 $ $Date: 2005/03/27 15:29:09 $
 */
public class Shutdown extends Thread {
	private static Logger log = LoggerFactory.getLogger(Shutdown.class.getName());


	public static final int SIGTERM = 0;
	public static final int GM_SHUTDOWN = 1;
	public static final int GM_RESTART = 2;
	public static final int ABORT = 3;
	private static final String[] MODE_TEXT = {"SIGTERM", "shutting down", "restarting", "aborting"};

	private int shutdownMode = SIGTERM;
	private static ShutdownTask task = null;

	private boolean shuttingDown = false;

	public void startShutdown(Player activeChar, int seconds, boolean restart) {
		String text = null;
		if (activeChar != null) {
			text = "GM: " + activeChar.getName() + " (" + activeChar.getObjectId() + ") issued shutdown command.";
		}

		startShutdown(text, seconds, restart);
	}

	/**
	 * This functions starts a shutdown countdown
	 *
	 * @param seconds seconds until shutdown
	 * @param restart true if the server will restart after shutdown
	 */
	public void startShutdown(String text, int seconds, boolean restart) {
		if (restart) {
			shutdownMode = GM_RESTART;
		} else {
			shutdownMode = GM_SHUTDOWN;
		}

		if (text != null) {
			log.info(text);
		}
		log.info(MODE_TEXT[shutdownMode] + " in " + seconds + " seconds!");

		if (shutdownMode > 0) {
			switch (seconds) {
				case 540:
				case 480:
				case 420:
				case 360:
				case 300:
				case 240:
				case 180:
				case 120:
				case 60:
				case 30:
				case 10:
				case 5:
				case 4:
				case 3:
				case 2:
				case 1:
					break;
				default:
					sendServerQuit(seconds, restart);
			}
		}

		if (task != null) {
			task.abort();
		}

		//		 the main instance should only run for shutdown hook, so we start a new instance
		task = new ShutdownTask(seconds, restart);
		task.start();
	}

	/**
	 * This function aborts a running countdown
	 *
	 * @param activeChar GM who issued the abort command
	 */
	public void abort(Player activeChar) {
		log.warn("GM: " + activeChar.getName() + " (" + activeChar.getObjectId() + ") issued shutdown ABORT. " + MODE_TEXT[shutdownMode] +
				" has been stopped!");
		if (task != null) {
			task.abort();
			Announcements an = Announcements.getInstance();
			an.announceToAll("Server aborts " + MODE_TEXT[shutdownMode] + " and continues normal operation!");
		}
	}

	/**
	 * This function notifies the players and the console log about the coming shutdown
	 *
	 * @param seconds seconds untill shutdown
	 * @param restart true if the server will restart after shutdown
	 */
	private void sendServerQuit(int seconds, boolean restart) {
		if (restart) {
			Broadcast.toAllOnlinePlayers(new ExShowScreenMessage("Restarting in " + seconds + " seconds", 5000));
			Announcements.getInstance().announceToAll("The server is restarting in " + seconds + " seconds. Find a safe place to log out.");
		} else {
			SystemMessage sysm = SystemMessage.getSystemMessage(SystemMessageId.THE_SERVER_WILL_BE_COMING_DOWN_IN_S1_SECONDS);
			sysm.addNumber(seconds);
			Broadcast.toAllOnlinePlayers(sysm);
		}
	}

	/**
	 * this function is called, when a new thread starts
	 * <p>
	 * if this thread is the thread of getInstance, then this is the shutdown hook
	 * and we save all data and disconnect all clients.
	 * <p>
	 * after this thread ends, the server will completely exit
	 * <p>
	 * if this is not the thread of getInstance, then this is a countdown thread.
	 * we start the countdown, and when we finished it, and it was not aborted,
	 * we tell the shutdown-hook why we call exit, and then call exit
	 * <p>
	 * when the exit status of the server is 1, startServer.sh / startServer.bat
	 * will restart the server.
	 */
	@Override
	public void run() {
		shuttingDown = true;
		try {
			if ((Config.OFFLINE_TRADE_ENABLE || Config.OFFLINE_CRAFT_ENABLE) && Config.RESTORE_OFFLINERS) {
				OfflineTradersTable.INSTANCE.storeOffliners();
			}
		} catch (Throwable t) {
			log.warn("Error saving offline shops.", t);
		}

		try {
			if (Config.OFFLINE_BUFFERS_ENABLE && Config.OFFLINE_BUFFERS_RESTORE) {
				CustomOfflineBuffersManager.getInstance().storeOfflineBuffers();
			}
		} catch (Throwable t) {
			log.warn("Error saving offline buffers.", t);
		}

		try {
			disconnectAllCharacters();
			log.info("All players disconnected.");
		} catch (Throwable t) {
			log.warn("Something went wrong while disconnecting players: " + t.getMessage());
			t.printStackTrace();
		}

		// ensure all services are stopped
		try {
			TimeController.getInstance().stopTimer();
		} catch (Throwable t) {
			log.warn("Something went wrong while stopping GameTimeController: " + t.getMessage());
			t.printStackTrace();
		}

		// stop all threadpools
		ThreadPoolManager.getInstance().shutdown();

		try {
			LoginServerThread.getInstance().interrupt();
		} catch (Throwable t) {
			log.warn("Something went wrong while shutting down Login Server connection: " + t.getMessage());
			t.printStackTrace();
		}

		// last byebye, save all data and quit this server
		saveData();

		// saveData sends messages to exit players, so shutdown selector after it
		try {
			Server.gameServer.getSelectorThread().shutdown();
		} catch (Throwable t) {
			log.warn("Something went wrong while shutting down selector thread: " + t.getMessage());
			t.printStackTrace();
		}

		// commit data, last chance
		try {
			DatabasePool.getInstance().shutdown();
		} catch (Throwable t) {
			log.warn("Something went wrong while shutting down DB connection: " + t.getMessage());
			t.printStackTrace();
		}

		// server will quit, when this function ends.
		if (shutdownMode == GM_RESTART) {
			Runtime.getRuntime().halt(2);
		} else {
			Runtime.getRuntime().halt(0);
		}
	}

	/**
	 * this sends a last byebye, disconnects all players and saves data
	 */
	private void saveData() {
		switch (shutdownMode) {
			case SIGTERM:
				log.info("SIGTERM received. Shutting down NOW!");
				break;
			case GM_SHUTDOWN:
				log.info("GM shutdown received. Shutting down NOW!");
				break;
			case GM_RESTART:
				log.info("GM restart received. Restarting NOW!");
				break;
		}

		/*if (Config.ACTIVATE_POSITION_RECORDER)
			Universe.getInstance().implode(true);*/

		SpawnDataManager.getInstance().saveDbSpawnData();
		log.info("SpawnDataManager: All spawn dynamic data saved");
		GrandBossManager.getInstance().cleanUp();
		log.info("GrandBossManager: All Grand Boss info saved");
		TradeController.INSTANCE.dataCountStore();
		log.info("TradeController: All count Item Saved");
		ItemAuctionManager.getInstance().shutdown();
		log.info("Item Auctions shut down");
		Olympiad.getInstance().saveOlympiadStatus();
		log.info("Olympiad System: Data saved");
		HeroesManager.getInstance().shutdown();
		log.info("Hero System: Data saved");
		ClanTable.getInstance().storeClanScore();
		log.info("Clan System: Data saved");
		ClanWarManager.getInstance().storeWarData();
		log.info("Clan War System: Data saved");

		// Save Cursed Weapons data before closing.
		CursedWeaponsManager.getInstance().saveData();
		log.info("Cursed Weapon data saved");

		// Save all manor data
		CastleManorManager.getInstance().save();
		log.info("Manor data saved");

		// Save all global (non-player specific) Quest data that needs to persist after reboot
		QuestManager.getInstance().save();
		log.info("Global Quest data saved");

		// Save all global variables data
		GlobalVariablesManager.getInstance().saveVars();
		log.info("Global Variables saved");

		//Save items on ground before closing
		if (Config.SAVE_DROPPED_ITEM) {
			ItemsOnGroundManager.getInstance().saveInDb();
			ItemsOnGroundManager.getInstance().cleanUp();
			log.info("ItemsOnGroundManager: All items on ground saved!!");
		}

		if (Config.ENABLE_CUSTOM_DAMAGE_MANAGER) {
			DamageManager.getInstance().saveData();
		}

		if (Config.ENABLE_CUSTOM_LOTTERY) {
			LotterySystem.getInstance().saveData();
		}
	}

	/**
	 * this disconnects all clients from the server
	 */
	public void disconnectAllCharacters() {
		Collection<Player> pls = World.getInstance().getAllPlayers().values();
		//synchronized (World.getInstance().getAllPlayers())
		{
			for (Player player : pls) {
				if (player == null) {
					continue;
				}

				// Log out character
				try {
					L2GameClient client = player.getClient();
					if (client != null && !client.isDetached()) {
						client.close(ServerClose.STATIC_PACKET);
						client.setActiveChar(null);
						player.setClient(null);
					}
				} catch (Throwable t) {
					log.warn("Failed to log out char " + player, t);
				}
			}

			for (Player player : pls) {
				if (player == null) {
					continue;
				}

				// Store character
				try {
					player.deleteMe();
				} catch (Throwable t) {
					log.warn("Failed to store char " + player, t);
				}
			}
		}
	}

	public boolean isShuttingDown() {
		return shuttingDown;
	}

	private class ShutdownTask extends Thread {
		private int secondsShut;

		/**
		 * This creates a countdown instance of Shutdown.
		 *
		 * @param seconds how many seconds until shutdown
		 * @param restart true is the server shall restart after shutdown
		 */
		public ShutdownTask(int seconds, boolean restart) {
			if (seconds < 0) {
				seconds = 0;
			}
			secondsShut = seconds;
			if (restart) {
				shutdownMode = GM_RESTART;
			} else {
				shutdownMode = GM_SHUTDOWN;
			}
		}

		@Override
		public void run() {
			// gm shutdown: send warnings and then call exit to start shutdown sequence
			countdown();
			// last point where logging is operational :(
			log.warn("GM shutdown countdown is over. " + MODE_TEXT[shutdownMode] + " NOW!");
			switch (shutdownMode) {
				case GM_SHUTDOWN:
					System.exit(0);
					break;
				case GM_RESTART:
					System.exit(2);
					break;
			}
		}

		/**
		 * set shutdown mode to ABORT
		 */
		private void abort() {
			shutdownMode = ABORT;
		}

		/**
		 * this counts the countdown and reports it to all players
		 * countdown is aborted if mode changes to ABORT
		 */
		private void countdown() {
			try {
				while (secondsShut > 0) {
					switch (secondsShut) {
						case 540:
							sendServerQuit(540, shutdownMode == GM_RESTART);
							break;
						case 480:
							sendServerQuit(480, shutdownMode == GM_RESTART);
							break;
						case 420:
							sendServerQuit(420, shutdownMode == GM_RESTART);
							break;
						case 360:
							sendServerQuit(360, shutdownMode == GM_RESTART);
							break;
						case 300:
							sendServerQuit(300, shutdownMode == GM_RESTART);
							break;
						case 240:
							sendServerQuit(240, shutdownMode == GM_RESTART);
							break;
						case 180:
							sendServerQuit(180, shutdownMode == GM_RESTART);
							break;
						case 120:
							sendServerQuit(120, shutdownMode == GM_RESTART);
							break;
						case 60:
							sendServerQuit(60, shutdownMode == GM_RESTART);
							break;
						case 30:
							sendServerQuit(30, shutdownMode == GM_RESTART);
							break;
						case 10:
							sendServerQuit(10, shutdownMode == GM_RESTART);
							break;
						case 5:
							sendServerQuit(5, shutdownMode == GM_RESTART);
							break;
						case 4:
							sendServerQuit(4, shutdownMode == GM_RESTART);
							break;
						case 3:
							sendServerQuit(3, shutdownMode == GM_RESTART);
							break;
						case 2:
							sendServerQuit(2, shutdownMode == GM_RESTART);
							break;
						case 1:
							sendServerQuit(1, shutdownMode == GM_RESTART);
							break;
					}

					secondsShut--;

					int delay = 1000; //milliseconds
					Thread.sleep(delay);

					if (shutdownMode == ABORT) {
						break;
					}
				}
			} catch (InterruptedException e) {
				//this will never happen
			}
		}
	}

	/**
	 * get the shutdown-hook instance
	 * the shutdown-hook instance is created by the first call of this function,
	 * but it has to be registrered externaly.
	 *
	 * @return instance of Shutdown, to be used as shutdown hook
	 */
	public static Shutdown getInstance() {
		return SingletonHolder.instance;
	}

	@SuppressWarnings("synthetic-access")
	private static class SingletonHolder {
		protected static final Shutdown instance = new Shutdown();
	}
}
